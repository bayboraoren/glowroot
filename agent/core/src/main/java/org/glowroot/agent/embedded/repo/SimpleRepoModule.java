/*
 * Copyright 2011-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.embedded.repo;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.H2DatabaseStats;
import org.glowroot.common.config.EmbeddedStorageConfig;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.repo.TraceAttributeNameRepository;
import org.glowroot.common.repo.TransactionTypeRepository;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.common.repo.util.HttpClient;
import org.glowroot.common.repo.util.MailService;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static java.util.concurrent.TimeUnit.MINUTES;

public class SimpleRepoModule {

    private static final int CURR_SCHEMA_VERSION = 2;

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private final DataSource dataSource;
    private final ImmutableList<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final EnvironmentDao environmentDao;
    private final TransactionTypeDao transactionTypeDao;
    private final AggregateDao aggregateDao;
    private final TraceAttributeNameDao traceAttributeNameDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final TriggeredAlertDao triggeredAlertDao;
    private final ConfigRepository configRepository;
    private final RepoAdmin repoAdmin;
    private final RollupLevelService rollupLevelService;
    private final AlertingService alertingService;
    private final HttpClient httpClient;
    private final @Nullable ReaperRunnable reaperRunnable;

    public SimpleRepoModule(DataSource dataSource, File dataDir, Clock clock, Ticker ticker,
            ConfigRepository configRepository,
            @Nullable ScheduledExecutorService backgroundExecutor) throws Exception {
        if (!dataDir.exists() && !dataDir.mkdir()) {
            throw new IOException("Could not create directory: " + dataDir.getAbsolutePath());
        }
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        EmbeddedStorageConfig storageConfig = configRepository.getEmbeddedStorageConfig();
        List<CappedDatabase> rollupCappedDatabases = Lists.newArrayList();
        for (int i = 0; i < storageConfig.rollupCappedDatabaseSizesMb().size(); i++) {
            File file = new File(dataDir, "rollup-" + i + "-detail.capped.db");
            int sizeKb = storageConfig.rollupCappedDatabaseSizesMb().get(i) * 1024;
            rollupCappedDatabases.add(new CappedDatabase(file, sizeKb, ticker));
        }
        this.rollupCappedDatabases = ImmutableList.copyOf(rollupCappedDatabases);
        traceCappedDatabase = new CappedDatabase(new File(dataDir, "trace-detail.capped.db"),
                storageConfig.traceCappedDatabaseSizeMb() * 1024, ticker);

        SchemaVersionDao schemaVersionDao = new SchemaVersionDao(dataSource);
        Integer schemaVersion = schemaVersionDao.getSchemaVersion();

        environmentDao = new EnvironmentDao(dataSource);
        transactionTypeDao = new TransactionTypeDao(dataSource);
        rollupLevelService = new RollupLevelService(configRepository, clock);
        FullQueryTextDao fullQueryTextDao = new FullQueryTextDao(dataSource);
        aggregateDao = new AggregateDao(dataSource, this.rollupCappedDatabases, configRepository,
                transactionTypeDao, fullQueryTextDao);
        traceAttributeNameDao = new TraceAttributeNameDao(dataSource);
        traceDao = new TraceDao(dataSource, traceCappedDatabase, transactionTypeDao,
                fullQueryTextDao, traceAttributeNameDao);
        GaugeNameDao gaugeNameDao = new GaugeNameDao(dataSource);
        gaugeValueDao = new GaugeValueDao(dataSource, gaugeNameDao, clock);
        triggeredAlertDao = new TriggeredAlertDao(dataSource, schemaVersion);

        if (schemaVersion == null || schemaVersion < CURR_SCHEMA_VERSION) {
            schemaVersionDao.updateSchemaVersion(CURR_SCHEMA_VERSION);
        }

        repoAdmin = new RepoAdminImpl(dataSource, rollupCappedDatabases, traceCappedDatabase,
                configRepository, environmentDao, gaugeValueDao, gaugeNameDao, transactionTypeDao,
                fullQueryTextDao, traceAttributeNameDao);

        httpClient = new HttpClient(configRepository);

        alertingService = new AlertingService(configRepository, triggeredAlertDao, aggregateDao,
                gaugeValueDao, rollupLevelService, new MailService(), httpClient);
        if (backgroundExecutor == null) {
            reaperRunnable = null;
        } else {
            reaperRunnable = new ReaperRunnable(configRepository, aggregateDao, traceDao,
                    gaugeValueDao, gaugeNameDao, transactionTypeDao, fullQueryTextDao, clock);
            reaperRunnable.scheduleWithFixedDelay(backgroundExecutor,
                    SNAPSHOT_REAPER_PERIOD_MINUTES, MINUTES);
        }
    }

    public void registerMBeans(PlatformMBeanServerLifecycle platformMBeanServerLifecycle) {
        for (int i = 0; i < rollupCappedDatabases.size(); i++) {
            platformMBeanServerLifecycle.lazyRegisterMBean(
                    new RollupCappedDatabaseStats(rollupCappedDatabases.get(i)),
                    "org.glowroot:type=RollupCappedDatabase" + i);
        }
        platformMBeanServerLifecycle.lazyRegisterMBean(
                new TraceCappedDatabaseStats(traceCappedDatabase),
                "org.glowroot:type=TraceCappedDatabase");
        platformMBeanServerLifecycle.lazyRegisterMBean(new H2DatabaseStats(dataSource),
                "org.glowroot:type=H2Database");
    }

    public EnvironmentDao getEnvironmentDao() {
        return environmentDao;
    }

    public TransactionTypeRepository getTransactionTypeRepository() {
        return transactionTypeDao;
    }

    public AggregateDao getAggregateDao() {
        return aggregateDao;
    }

    public TraceAttributeNameRepository getTraceAttributeNameRepository() {
        return traceAttributeNameDao;
    }

    public TraceDao getTraceDao() {
        return traceDao;
    }

    public GaugeValueDao getGaugeValueDao() {
        return gaugeValueDao;
    }

    public TriggeredAlertDao getTriggeredAlertDao() {
        return triggeredAlertDao;
    }

    public ConfigRepository getConfigRepository() {
        return configRepository;
    }

    public RepoAdmin getRepoAdmin() {
        return repoAdmin;
    }

    public RollupLevelService getRollupLevelService() {
        return rollupLevelService;
    }

    public AlertingService getAlertingService() {
        return alertingService;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (reaperRunnable != null) {
            reaperRunnable.cancel();
        }
        alertingService.close();
        for (CappedDatabase cappedDatabase : rollupCappedDatabases) {
            cappedDatabase.close();
        }
        traceCappedDatabase.close();
        dataSource.close();
    }
}
