/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.state.deployment;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.camunda.zeebe.db.ColumnFamily;
import io.camunda.zeebe.db.TransactionContext;
import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.db.impl.DbCompositeKey;
import io.camunda.zeebe.db.impl.DbForeignKey;
import io.camunda.zeebe.db.impl.DbInt;
import io.camunda.zeebe.db.impl.DbLong;
import io.camunda.zeebe.db.impl.DbNil;
import io.camunda.zeebe.db.impl.DbString;
import io.camunda.zeebe.engine.state.mutable.MutableDecisionState;
import io.camunda.zeebe.protocol.ZbColumnFamilies;
import io.camunda.zeebe.protocol.impl.record.value.deployment.DecisionRecord;
import io.camunda.zeebe.protocol.impl.record.value.deployment.DecisionRequirementsRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.agrona.DirectBuffer;

public final class DbDecisionState implements MutableDecisionState {

  private static final int CACHE_SIZE = 10_000;
  private final DbLong dbDecisionKey;
  private final DbForeignKey<DbLong> fkDecision;
  private final PersistedDecision dbPersistedDecision;
  private final DbString dbDecisionId;

  private final DbLong dbDecisionRequirementsKey;
  private final DbForeignKey<DbLong> fkDecisionRequirements;
  private final PersistedDecisionRequirements dbPersistedDecisionRequirements;
  private final DbString dbDecisionRequirementsId;

  private final DbCompositeKey<DbForeignKey<DbLong>, DbForeignKey<DbLong>>
      dbDecisionRequirementsKeyAndDecisionKey;
  private final ColumnFamily<DbCompositeKey<DbForeignKey<DbLong>, DbForeignKey<DbLong>>, DbNil>
      decisionKeyByDecisionRequirementsKey;

  private final ColumnFamily<DbLong, PersistedDecision> decisionsByKey;
  private final ColumnFamily<DbString, DbForeignKey<DbLong>> latestDecisionKeysByDecisionId;

  private final DbInt dbDecisionVersion;
  private final DbCompositeKey<DbString, DbInt> decisionIdAndVersion;
  private final ColumnFamily<DbCompositeKey<DbString, DbInt>, DbForeignKey<DbLong>>
      decisionKeyByDecisionIdAndVersion;

  private final ColumnFamily<DbLong, PersistedDecisionRequirements> decisionRequirementsByKey;
  private final ColumnFamily<DbString, DbForeignKey<DbLong>> latestDecisionRequirementsKeysById;

  private final DbInt dbDecisionRequirementsVersion;
  private final DbCompositeKey<DbString, DbInt> decisionRequirementsIdAndVersion;
  private final ColumnFamily<DbCompositeKey<DbString, DbInt>, DbForeignKey<DbLong>>
      decisionRequirementsKeyByIdAndVersion;

  private final LoadingCache<DirectBuffer, DbForeignKey<DbLong>>
      latestDecisionKeysByDecisionIdCache;
  private final LoadingCache<Long, PersistedDecision> decisionsByKeyCache;
  private final LoadingCache<DirectBuffer, DbForeignKey<DbLong>>
      latestDecisionRequirementsKeysByIdCache;
  private final LoadingCache<Long, PersistedDecisionRequirements> decisionRequirementsByKeyCache;
  private final LoadingCache<Long, List<PersistedDecision>> decisionsByDecisionRequirementsKey;

  public DbDecisionState(
      final ZeebeDb<ZbColumnFamilies> zeebeDb, final TransactionContext transactionContext) {
    dbDecisionKey = new DbLong();
    fkDecision = new DbForeignKey<>(dbDecisionKey, ZbColumnFamilies.DMN_DECISIONS);

    dbPersistedDecision = new PersistedDecision();
    decisionsByKey =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.DMN_DECISIONS, transactionContext, dbDecisionKey, dbPersistedDecision);

    dbDecisionId = new DbString();
    latestDecisionKeysByDecisionId =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.DMN_LATEST_DECISION_BY_ID,
            transactionContext,
            dbDecisionId,
            fkDecision);

    dbDecisionRequirementsKey = new DbLong();
    fkDecisionRequirements =
        new DbForeignKey<>(dbDecisionRequirementsKey, ZbColumnFamilies.DMN_DECISION_REQUIREMENTS);
    dbPersistedDecisionRequirements = new PersistedDecisionRequirements();
    decisionRequirementsByKey =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.DMN_DECISION_REQUIREMENTS,
            transactionContext,
            dbDecisionRequirementsKey,
            dbPersistedDecisionRequirements);

    dbDecisionRequirementsId = new DbString();
    latestDecisionRequirementsKeysById =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.DMN_LATEST_DECISION_REQUIREMENTS_BY_ID,
            transactionContext,
            dbDecisionRequirementsId,
            fkDecisionRequirements);

    dbDecisionRequirementsKeyAndDecisionKey =
        new DbCompositeKey<>(fkDecisionRequirements, fkDecision);
    decisionKeyByDecisionRequirementsKey =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.DMN_DECISION_KEY_BY_DECISION_REQUIREMENTS_KEY,
            transactionContext,
            dbDecisionRequirementsKeyAndDecisionKey,
            DbNil.INSTANCE);

    dbDecisionVersion = new DbInt();
    decisionIdAndVersion = new DbCompositeKey<>(dbDecisionId, dbDecisionVersion);
    decisionKeyByDecisionIdAndVersion =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.DMN_DECISION_KEY_BY_DECISION_ID_AND_VERSION,
            transactionContext,
            decisionIdAndVersion,
            fkDecision);

    dbDecisionRequirementsVersion = new DbInt();
    decisionRequirementsIdAndVersion =
        new DbCompositeKey<>(dbDecisionRequirementsId, dbDecisionRequirementsVersion);
    decisionRequirementsKeyByIdAndVersion =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.DMN_DECISION_REQUIREMENTS_KEY_BY_DECISION_REQUIREMENT_ID_AND_VERSION,
            transactionContext,
            decisionRequirementsIdAndVersion,
            fkDecisionRequirements);

    latestDecisionKeysByDecisionIdCache =
        Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .build(
                decisionId -> {
                  dbDecisionRequirementsId.wrapBuffer(decisionId);
                  return latestDecisionKeysByDecisionId.get(dbDecisionRequirementsId);
                });

    decisionsByKeyCache =
        Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .build(
                decisionKey -> {
                  dbDecisionKey.wrapLong(decisionKey);
                  return decisionsByKey.get(dbDecisionKey).copy();
                });

    latestDecisionRequirementsKeysByIdCache =
        Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .build(
                decisionId -> {
                  dbDecisionId.wrapBuffer(decisionId);
                  return latestDecisionRequirementsKeysById.get(dbDecisionId);
                });

    decisionRequirementsByKeyCache =
        Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .build(
                decisionKey -> {
                  dbDecisionKey.wrapLong(decisionKey);
                  return decisionRequirementsByKey.get(dbDecisionKey);
                });

    decisionsByDecisionRequirementsKey =
        Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .build(
                decisionRequirementsKey -> {
                  final List<PersistedDecision> decisions = new ArrayList<>();
                  dbDecisionRequirementsKey.wrapLong(decisionRequirementsKey);
                  decisionKeyByDecisionRequirementsKey.whileEqualPrefix(
                      dbDecisionRequirementsKey,
                      ((key, nil) -> {
                        final var decisionKey = key.second();
                        findDecisionByKey(decisionKey.inner().getValue()).ifPresent(decisions::add);
                      }));
                  return decisions;
                });
  }

  @Override
  public Optional<PersistedDecision> findLatestDecisionById(final DirectBuffer decisionId) {
    return Optional.ofNullable(latestDecisionKeysByDecisionIdCache.get(decisionId))
        .flatMap(decisionKey -> findDecisionByKey(decisionKey.inner().getValue()));
  }

  @Override
  public Optional<PersistedDecision> findDecisionByKey(final long decisionKey) {
    return Optional.ofNullable(decisionsByKeyCache.get(decisionKey));
  }

  @Override
  public Optional<PersistedDecisionRequirements> findLatestDecisionRequirementsById(
      final DirectBuffer decisionRequirementsId) {
    return Optional.ofNullable(latestDecisionRequirementsKeysByIdCache.get(decisionRequirementsId))
        .map((requirementsKey) -> requirementsKey.inner().getValue())
        .flatMap(this::findDecisionRequirementsByKey);
  }

  @Override
  public Optional<PersistedDecisionRequirements> findDecisionRequirementsByKey(
      final long decisionRequirementsKey) {
    return Optional.ofNullable(decisionRequirementsByKeyCache.get(decisionRequirementsKey))
        .map(PersistedDecisionRequirements::copy);
  }

  @Override
  public List<PersistedDecision> findDecisionsByDecisionRequirementsKey(
      final long decisionRequirementsKey) {
    return decisionsByDecisionRequirementsKey.get(decisionRequirementsKey);
  }

  /**
   * Query decisions to find the key of the decision with the version that comes before the given
   * version.
   *
   * @param decisionId the id of the decision
   * @param currentVersion the current version
   * @return the decision key of the version that's previous to the given version
   */
  private Optional<Long> findPreviousVersionDecisionKey(
      final DirectBuffer decisionId, final int currentVersion) {
    final Map<Integer, Long> decisionKeysByVersion = new HashMap<>();

    dbDecisionId.wrapBuffer(decisionId);
    decisionKeyByDecisionIdAndVersion.whileEqualPrefix(
        dbDecisionId,
        ((key, decisionKey) -> {
          if (key.second().getValue() < currentVersion) {
            decisionKeysByVersion.put(key.second().getValue(), decisionKey.inner().getValue());
          }
        }));

    if (decisionKeysByVersion.isEmpty()) {
      return Optional.empty();
    } else {
      final Integer previousVersion = Collections.max(decisionKeysByVersion.keySet());
      return Optional.of(decisionKeysByVersion.get(previousVersion));
    }
  }

  private Optional<Long> findPreviousVersionDecisionRequirementsKey(
      final DirectBuffer decisionRequirementsId, final int currentVersion) {
    final Map<Integer, Long> decisionRequirementsKeysByVersion = new HashMap<>();

    dbDecisionRequirementsId.wrapBuffer(decisionRequirementsId);
    decisionRequirementsKeyByIdAndVersion.whileEqualPrefix(
        dbDecisionRequirementsId,
        ((key, drgKey) -> {
          if (key.second().getValue() < currentVersion) {
            decisionRequirementsKeysByVersion.put(
                key.second().getValue(), drgKey.inner().getValue());
          }
        }));

    if (decisionRequirementsKeysByVersion.isEmpty()) {
      return Optional.empty();
    } else {
      final Integer previousVersion = Collections.max(decisionRequirementsKeysByVersion.keySet());
      return Optional.of(decisionRequirementsKeysByVersion.get(previousVersion));
    }
  }

  @Override
  public void storeDecisionRecord(final DecisionRecord record) {
    dbDecisionKey.wrapLong(record.getDecisionKey());
    dbPersistedDecision.wrap(record);
    decisionsByKey.upsert(dbDecisionKey, dbPersistedDecision);

    dbDecisionKey.wrapLong(record.getDecisionKey());
    dbDecisionRequirementsKey.wrapLong(record.getDecisionRequirementsKey());
    decisionKeyByDecisionRequirementsKey.upsert(
        dbDecisionRequirementsKeyAndDecisionKey, DbNil.INSTANCE);

    dbDecisionId.wrapString(record.getDecisionId());
    dbDecisionVersion.wrapInt(record.getVersion());
    decisionKeyByDecisionIdAndVersion.upsert(decisionIdAndVersion, fkDecision);

    updateLatestDecisionVersion(record);
  }

  @Override
  public void storeDecisionRequirements(final DecisionRequirementsRecord record) {
    dbDecisionRequirementsKey.wrapLong(record.getDecisionRequirementsKey());
    dbPersistedDecisionRequirements.wrap(record);
    decisionRequirementsByKey.upsert(dbDecisionRequirementsKey, dbPersistedDecisionRequirements);

    dbDecisionRequirementsId.wrapString(record.getDecisionRequirementsId());
    dbDecisionRequirementsVersion.wrapInt(record.getDecisionRequirementsVersion());
    decisionRequirementsKeyByIdAndVersion.upsert(
        decisionRequirementsIdAndVersion, fkDecisionRequirements);

    updateLatestDecisionRequirementsVersion(record);
  }

  @Override
  public void deleteDecision(final DecisionRecord record) {
    findLatestDecisionById(record.getDecisionIdBuffer())
        .map(PersistedDecision::getVersion)
        .ifPresent(
            latestVersion -> {
              if (latestVersion == record.getVersion()) {
                dbDecisionId.wrapBuffer(record.getDecisionIdBuffer());
                findPreviousVersionDecisionKey(record.getDecisionIdBuffer(), record.getVersion())
                    .ifPresentOrElse(
                        previousDecisionKey -> {
                          // Update the latest decision version
                          dbDecisionKey.wrapLong(previousDecisionKey);
                          latestDecisionKeysByDecisionId.update(dbDecisionId, fkDecision);
                        },
                        () -> {
                          // Clear the latest decision version
                          latestDecisionKeysByDecisionId.deleteExisting(dbDecisionId);
                        });
              }
            });

    dbDecisionRequirementsKey.wrapLong(record.getDecisionRequirementsKey());
    dbDecisionKey.wrapLong(record.getDecisionKey());
    dbDecisionId.wrapBuffer(record.getDecisionIdBuffer());
    dbDecisionVersion.wrapInt(record.getVersion());

    decisionKeyByDecisionRequirementsKey.deleteExisting(dbDecisionRequirementsKeyAndDecisionKey);
    decisionsByKey.deleteExisting(dbDecisionKey);
    decisionKeyByDecisionIdAndVersion.deleteExisting(decisionIdAndVersion);
  }

  @Override
  public void deleteDecisionRequirements(final DecisionRequirementsRecord record) {
    findLatestDecisionRequirementsById(record.getDecisionRequirementsIdBuffer())
        .map(PersistedDecisionRequirements::getDecisionRequirementsVersion)
        .ifPresent(
            latestVersion -> {
              if (latestVersion == record.getDecisionRequirementsVersion()) {
                dbDecisionRequirementsId.wrapBuffer(record.getDecisionRequirementsIdBuffer());
                findPreviousVersionDecisionRequirementsKey(
                        record.getDecisionRequirementsIdBuffer(),
                        record.getDecisionRequirementsVersion())
                    .ifPresentOrElse(
                        previousDrgKey -> {
                          // Update the latest decision version
                          dbDecisionRequirementsKey.wrapLong(previousDrgKey);
                          latestDecisionRequirementsKeysById.update(
                              dbDecisionRequirementsId, fkDecisionRequirements);
                        },
                        () -> {
                          // Clear the latest decision version
                          latestDecisionRequirementsKeysById.deleteExisting(
                              dbDecisionRequirementsId);
                        });
              }
            });

    dbDecisionRequirementsKey.wrapLong(record.getDecisionRequirementsKey());
    dbDecisionRequirementsId.wrapBuffer(record.getDecisionRequirementsIdBuffer());
    dbDecisionRequirementsVersion.wrapInt(record.getDecisionRequirementsVersion());

    decisionRequirementsByKey.deleteExisting(dbDecisionRequirementsKey);
    decisionRequirementsKeyByIdAndVersion.deleteExisting(decisionRequirementsIdAndVersion);
  }

  private void updateLatestDecisionVersion(final DecisionRecord record) {
    findLatestDecisionById(record.getDecisionIdBuffer())
        .ifPresentOrElse(
            previousVersion -> {
              if (record.getVersion() > previousVersion.getVersion()) {
                updateDecisionAsLatestVersion(record);
              }
            },
            () -> insertDecisionAsLatestVersion(record));
  }

  private void updateDecisionAsLatestVersion(final DecisionRecord record) {
    dbDecisionId.wrapBuffer(record.getDecisionIdBuffer());
    dbDecisionKey.wrapLong(record.getDecisionKey());
    latestDecisionKeysByDecisionId.update(dbDecisionId, fkDecision);
  }

  private void insertDecisionAsLatestVersion(final DecisionRecord record) {
    dbDecisionId.wrapBuffer(record.getDecisionIdBuffer());
    dbDecisionKey.wrapLong(record.getDecisionKey());
    latestDecisionKeysByDecisionId.upsert(dbDecisionId, fkDecision);
  }

  private void updateLatestDecisionRequirementsVersion(final DecisionRequirementsRecord record) {
    findLatestDecisionRequirementsById(record.getDecisionRequirementsIdBuffer())
        .ifPresentOrElse(
            previousVersion -> {
              if (record.getDecisionRequirementsVersion()
                  > previousVersion.getDecisionRequirementsVersion()) {
                updateDecisionRequirementsAsLatestVersion(record);
              }
            },
            () -> insertDecisionRequirementsAsLatestVersion(record));
  }

  private void updateDecisionRequirementsAsLatestVersion(final DecisionRequirementsRecord record) {
    dbDecisionRequirementsId.wrapBuffer(record.getDecisionRequirementsIdBuffer());
    dbDecisionRequirementsKey.wrapLong(record.getDecisionRequirementsKey());
    latestDecisionRequirementsKeysById.update(dbDecisionRequirementsId, fkDecisionRequirements);
  }

  private void insertDecisionRequirementsAsLatestVersion(final DecisionRequirementsRecord record) {
    dbDecisionRequirementsId.wrapBuffer(record.getDecisionRequirementsIdBuffer());
    dbDecisionRequirementsKey.wrapLong(record.getDecisionRequirementsKey());
    latestDecisionRequirementsKeysById.upsert(dbDecisionRequirementsId, fkDecisionRequirements);
  }
}
