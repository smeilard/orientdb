/*
 * Copyright 1999-2010 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.index;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Set;

import com.orientechnologies.common.collection.ONavigableMap;
import com.orientechnologies.common.concur.resource.OSharedResource;
import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.common.profiler.OProfiler;
import com.orientechnologies.common.profiler.OProfiler.OProfilerHookValue;
import com.orientechnologies.orient.core.annotation.OBeforeSerialization;
import com.orientechnologies.orient.core.annotation.ODocumentInstance;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.ODatabaseListener;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.exception.OSerializationException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.record.impl.ORecordBytes;
import com.orientechnologies.orient.core.serialization.OSerializableStream;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializerListRID;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializerLiteral;
import com.orientechnologies.orient.core.type.tree.OMVRBTreeDatabaseLazySave;

/**
 * Handles indexing when records change.
 * 
 * @author Luca Garulli
 * 
 */
@SuppressWarnings("serial")
public abstract class OIndexMVRBTreeAbstract extends OSharedResource implements OIndex, ODatabaseListener {
	protected static final String																	CONFIG_MAP_RID	= "mapRid";
	protected static final String																	CONFIG_CLUSTERS	= "clusters";
	protected String																							name;
	protected String																							type;
	protected OMVRBTreeDatabaseLazySave<Object, Set<ORecord<?>>>	map;
	protected Set<String>																					clustersToIndex	= new LinkedHashSet<String>();
	protected OIndexCallback																			callback;
	protected boolean																							automatic;
	protected Set<Object>																					tempItems				= new HashSet<Object>();

	@ODocumentInstance
	protected ODocument																						configuration;

	public OIndexMVRBTreeAbstract(final String iType) {
		type = iType;
	}

	/**
	 * Creates the index.
	 * 
	 * @param iDatabase
	 *          Current Database instance
	 * @param iProperty
	 *          Owner property
	 * @param iClusterIndexName
	 *          Cluster name where to place the TreeMap
	 * @param iProgressListener
	 *          Listener to get called on progress
	 */
	public OIndex create(final String iName, final ODatabaseRecord iDatabase, final String iClusterIndexName,
			final int[] iClusterIdsToIndex, final OProgressListener iProgressListener, final boolean iAutomatic) {
		configuration = new ODocument(iDatabase);
		automatic = iAutomatic;

		if (iClusterIdsToIndex != null)
			for (int id : iClusterIdsToIndex)
				clustersToIndex.add(iDatabase.getClusterNameById(id));

		name = iName;
		installProfilerHooks();

		iDatabase.registerListener(this);

		map = new OMVRBTreeDatabaseLazySave<Object, Set<ORecord<?>>>(iDatabase, iClusterIndexName, OStreamSerializerLiteral.INSTANCE,
				OStreamSerializerListRID.INSTANCE);
		rebuild(iProgressListener);
		return this;
	}

	public OIndex loadFromConfiguration(final ODocument iConfig) {
		final ORID rid = (ORID) iConfig.field(CONFIG_MAP_RID, ORID.class);
		if (rid == null)
			return null;
		
		configuration = iConfig;
		name = configuration.field(OIndex.CONFIG_NAME);
		automatic = (Boolean) (configuration.field(OIndex.CONFIG_AUTOMATIC) != null ? configuration.field(OIndex.CONFIG_AUTOMATIC)
				: true);
		clustersToIndex.clear();

		final Collection<? extends String> clusters = configuration.field(CONFIG_CLUSTERS);
		if (clusters != null)
			clustersToIndex.addAll(clusters);

		load(iConfig.getDatabase(), rid);

		return this;
	}

	public OIndex loadFromConfiguration(final ODatabaseRecord iDatabase, final ORID iRecordId) {
		load(iDatabase, iRecordId);
		return this;
	}

	@SuppressWarnings("unchecked")
	public Set<ORecord<?>> get(Object iKey) {
		acquireSharedLock();

		try {
			final Set<ORecord<?>> values = map.get(iKey);

			if (values == null)
				return Collections.EMPTY_SET;

			return values;

		} finally {
			releaseSharedLock();
		}
	}

	/**
	 * Returns a set of records with key between the range passed as parameter. Range bounds are included.
	 * 
	 * @param iRangeFrom
	 *          Starting range
	 * @param iRangeTo
	 *          Ending range
	 * @see #getBetween(Object, Object, boolean)
	 * @return
	 */
	public Set<ORecord<?>> getBetween(final Object iRangeFrom, final Object iRangeTo) {
		return getBetween(iRangeFrom, iRangeTo, true);
	}

	/**
	 * Returns a set of records with key between the range passed as parameter.
	 * 
	 * @param iRangeFrom
	 *          Starting range
	 * @param iRangeTo
	 *          Ending range
	 * @param iInclusive
	 *          Include from/to bounds
	 * @see #getBetween(Object, Object)
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public Set<ORecord<?>> getBetween(final Object iRangeFrom, final Object iRangeTo, final boolean iInclusive) {
		if (iRangeFrom.getClass() != iRangeTo.getClass())
			throw new IllegalArgumentException("Range from-to parameters are of different types");

		acquireSharedLock();

		try {
			final ONavigableMap<Object, Set<ORecord<?>>> subSet = map.subMap(iRangeFrom, iInclusive, iRangeTo, iInclusive);
			if (subSet == null)
				return Collections.EMPTY_SET;

			final Set<ORecord<?>> result = new HashSet<ORecord<?>>();
			for (Set<ORecord<?>> v : subSet.values()) {
				result.addAll(v);
			}

			return result;

		} finally {
			releaseSharedLock();
		}
	}

	public ORID getIdentity() {
		return map.getRecord().getIdentity();
	}

	public OIndex rebuild() {
		return rebuild(null);
	}

	/**
	 * Populate the index with all the existent records.
	 */
	public OIndex rebuild(final OProgressListener iProgressListener) {
		Object fieldValue;
		ODocument doc;

		clear();

		acquireExclusiveLock();

		try {

			int documentIndexed = 0;
			int documentNum = 0;
			long documentTotal = 0;

			for (String cluster : clustersToIndex)
				documentTotal += map.getDatabase().countClusterElements(cluster);

			if (iProgressListener != null)
				iProgressListener.onBegin(this, documentTotal);

			for (String clusterName : clustersToIndex)
				for (ORecord<?> record : map.getDatabase().browseCluster(clusterName)) {
					if (record instanceof ODocument) {
						doc = (ODocument) record;
						fieldValue = callback.getDocumentValueToIndex(doc);

						if (fieldValue != null) {
							put(fieldValue, doc);
							++documentIndexed;
						}
					}
					documentNum++;

					if (iProgressListener != null)
						iProgressListener.onProgress(this, documentNum, documentNum * 100f / documentTotal);
				}

			lazySave();

			if (iProgressListener != null)
				iProgressListener.onCompletition(this, true);

		} catch (Exception e) {
			if (iProgressListener != null)
				iProgressListener.onCompletition(this, false);

			clear();

			throw new OIndexException("Error on rebuilding the index for clusters: " + clustersToIndex, e);

		} finally {
			releaseExclusiveLock();
		}

		return this;
	}

	public OIndex remove(final Object key) {
		acquireSharedLock();

		try {
			map.remove(key);

		} finally {
			releaseSharedLock();
		}
		return this;
	}

	public OIndex load() {
		acquireExclusiveLock();

		try {
			map.load();

		} finally {
			releaseExclusiveLock();
		}
		return this;
	}

	public OIndex clear() {
		acquireExclusiveLock();

		try {
			map.clear();

		} finally {
			releaseExclusiveLock();
		}
		return this;
	}

	public OIndex delete() {
		map.delete();
		return this;
	}

	public OIndex lazySave() {
		acquireExclusiveLock();

		try {
			map.lazySave();

		} finally {
			releaseExclusiveLock();
		}

		return this;
	}

	public ORecordBytes getRecord() {
		return map.getRecord();
	}

	public Iterator<Entry<Object, Set<ORecord<?>>>> iterator() {
		acquireSharedLock();

		try {
			return map.entrySet().iterator();

		} finally {
			releaseSharedLock();
		}
	}

	protected void load(final ODatabaseRecord iDatabase, final ORID iRecordId) {
		installProfilerHooks();

		map = new OMVRBTreeDatabaseLazySave<Object, Set<ORecord<?>>>(iDatabase, iRecordId);
		map.load();

		iDatabase.registerListener(this);
	}

	public int getSize() {
		acquireSharedLock();

		try {
			return map.size();

		} finally {
			releaseSharedLock();
		}
	}

	public String getName() {
		return name;
	}

	public OIndex setName(final String name) {
		this.name = name;
		return this;
	}

	public String getType() {
		return type;
	}

	@Override
	public String toString() {
		return name + " (" + (type != null ? type : "?") + ")" + (map != null ? " " + map : "");
	}

	public OIndexCallback getCallback() {
		return callback;
	}

	public void setCallback(final OIndexCallback callback) {
		this.callback = callback;
	}

	@OBeforeSerialization
	public byte[] toStream() throws OSerializationException {
		configuration.field(OIndex.CONFIG_TYPE, type);
		configuration.field(OIndex.CONFIG_NAME, name);
		configuration.field(OIndex.CONFIG_AUTOMATIC, automatic);
		configuration.field(CONFIG_CLUSTERS, clustersToIndex, OType.EMBEDDEDSET);
		configuration.field(CONFIG_MAP_RID, map.getRecord().getIdentity());
		return configuration.toStream();
	}

	public OSerializableStream fromStream(final byte[] iStream) throws OSerializationException {
		configuration.fromStream(iStream);
		name = configuration.field(OIndex.CONFIG_NAME);
		automatic = (Boolean) (configuration.field(OIndex.CONFIG_AUTOMATIC) != null ? configuration.field(OIndex.CONFIG_AUTOMATIC)
				: true);
		clustersToIndex = configuration.field(CONFIG_CLUSTERS);
		map.getRecord().setIdentity((ORecordId) configuration.field(CONFIG_MAP_RID, ORID.class));
		return null;
	}

	public Set<String> getClusters() {
		return Collections.unmodifiableSet(clustersToIndex);
	}

	public OIndexMVRBTreeAbstract addCluster(final String iClusterName) {
		clustersToIndex.add(iClusterName);
		return this;
	}

	public void checkEntry(final ODocument iRecord, final Object iKey) {
	}

	public void unload() {
		map.unload();
	}

	public ODocument getConfiguration() {
		return configuration;
	}

	public boolean isAutomatic() {
		return automatic;
	}

	private void installProfilerHooks() {
		OProfiler.getInstance().registerHookValue("index." + name + ".items", new OProfilerHookValue() {
			public Object getValue() {
				return map != null ? map.size() : "-";
			}
		});

		OProfiler.getInstance().registerHookValue("index." + name + ".entryPointSize", new OProfilerHookValue() {
			public Object getValue() {
				return map != null ? map.getEntryPointSize() : "-";
			}
		});

		OProfiler.getInstance().registerHookValue("index." + name + ".maxUpdateBeforeSave", new OProfilerHookValue() {
			public Object getValue() {
				return map != null ? map.getMaxUpdatesBeforeSave() : "-";
			}
		});

		OProfiler.getInstance().registerHookValue("index." + name + ".optimizationThreshold", new OProfilerHookValue() {
			public Object getValue() {
				return map != null ? map.getOptimizeThreshold() : "-";
			}
		});
	}

	public void onCreate(ODatabase iDatabase) {
	}

	public void onDelete(ODatabase iDatabase) {
	}

	public void onOpen(ODatabase iDatabase) {
	}

	public void onBeforeTxBegin(ODatabase iDatabase) {
	}

	public void onBeforeTxRollback(ODatabase iDatabase) {
	}

	public void onAfterTxRollback(ODatabase iDatabase) {
	}

	public void onBeforeTxCommit(ODatabase iDatabase) {
	}

	/**
	 * Reset documents into the set to update its hashcode.
	 */
	public void onAfterTxCommit(ODatabase iDatabase) {
		if (tempItems.size() > 0) {
			for (Object key : tempItems) {
				Set<ORecord<?>> set = map.get(key);
				if (set != null) {
					// RE-ADD ALL THE ITEM TO UPDATE THE HASHCODE (CHANGED AFTER SAVE+COMMIT)
					final Set<ORecord<?>> newSet = new HashSet<ORecord<?>>();
					newSet.addAll(set);
					map.put(key, newSet);
				}
			}
		}
		tempItems.clear();
	}

	public void onClose(ODatabase iDatabase) {
	}
}