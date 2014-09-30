package org.dswarm.graph.batch;

import java.util.HashMap;
import java.util.Map;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.NodeType;
import org.dswarm.graph.hash.HashUtils;
import org.dswarm.graph.model.GraphStatics;
import org.mapdb.HTreeMap;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.index.lucene.unsafe.batchinsert.LuceneBatchInserterIndexProvider;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserterIndex;
import org.neo4j.unsafe.batchinsert.BatchInserterIndexProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.hppc.LongLongMap;
import com.carrotsearch.hppc.LongLongOpenHashMap;
import com.carrotsearch.hppc.LongObjectMap;
import com.carrotsearch.hppc.LongObjectOpenHashMap;
import com.carrotsearch.hppc.ObjectLongMap;
import com.carrotsearch.hppc.ObjectLongOpenHashMap;
import com.carrotsearch.hppc.cursors.LongLongCursor;
import com.carrotsearch.hppc.cursors.ObjectLongCursor;
import com.github.emboss.siphash.SipHash;
import com.github.emboss.siphash.SipKey;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;

/**
 * @author tgaengler
 */
public abstract class Neo4jProcessor {

	private static final Logger				LOG			= LoggerFactory.getLogger(Neo4jProcessor.class);

	protected int							addedLabels	= 0;

	private static final SipKey				SPEC_KEY	= new SipKey(HashUtils.bytesOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
																0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f));

	protected final BatchInserter			inserter;
	private BatchInserterIndex				resources;
	private BatchInserterIndex				resourcesWDataModel;
	private BatchInserterIndex				resourceTypes;

	protected final ObjectLongMap<String>	tempResourcesIndex;
	protected final ObjectLongMap<String>	tempResourcesWDataModelIndex;
	protected final ObjectLongMap<String>	tempResourceTypes;

	private BatchInserterIndex				values;
	protected final ObjectLongMap<String>	bnodes;
	private BatchInserterIndex				statementHashes;

	protected final LongLongMap				tempStatementHashes;

	protected final LongObjectMap<String>	nodeResourceMap;

	// private final TxMaker tx;
	// private DB mapdb;
	// private HTreeMap<char[], Long> mapdbResourcesIndex;
	// private HTreeMap<char[], Long> mapdbResourcesWDataModelIndex;
	// private HTreeMap<char[], Long> mapdbResourcesTypesIndex;
	// private HTreeMap<Long, Long> mapdbStatementHashesIndex;
	//
	// private DB snapshotMapdb;
	// private HTreeMap<char[], Long> snapshotMapdbResourcesIndex;
	// private HTreeMap<char[], Long> snapshotMapdbResourcesWDataModelIndex;
	// private HTreeMap<char[], Long> snapshotMapdbResourcesTypesIndex;
	// private HTreeMap<Long, Long> snapshotMapdbStatementHashesIndex;

	public Neo4jProcessor(final BatchInserter inserter) throws DMPGraphException {

		this.inserter = inserter;

		Neo4jProcessor.LOG.debug("start writing");

		bnodes = new ObjectLongOpenHashMap<>();
		nodeResourceMap = new LongObjectOpenHashMap<>();

		tempResourcesIndex = new ObjectLongOpenHashMap<>();
		tempResourcesWDataModelIndex = new ObjectLongOpenHashMap<>();
		tempResourceTypes = new ObjectLongOpenHashMap<>();
		tempStatementHashes = new LongLongOpenHashMap();

		// initIndices();

		// tx = DBMaker.newFileDB(new
		// File("target/testmapdb")).asyncWriteEnable().asyncWriteQueueSize(1000000).cacheDisable().snapshotEnable().cacheSoftRefEnable().mmapFileEnable().compressionEnable().closeOnJvmShutdown().makeTxMaker();
		// mapdb = tx.makeTx();
		// initMapDBIndices();
	}

	// protected void initMapDBIndices() {
	//
	// mapdbResourcesIndex =
	// mapdb.createHashMap("resources").keySerializer(Serializer.CHAR_ARRAY).valueSerializer(Serializer.LONG).makeOrGet();
	// mapdbResourcesWDataModelIndex =
	// mapdb.createHashMap("resources_w_data_model").keySerializer(Serializer.CHAR_ARRAY).valueSerializer(Serializer.LONG).makeOrGet();
	// mapdbResourcesTypesIndex =
	// mapdb.createHashMap("resource_types").keySerializer(Serializer.CHAR_ARRAY).valueSerializer(Serializer.LONG).makeOrGet();
	// mapdbStatementHashesIndex =
	// mapdb.createHashMap("statement_hashes").keySerializer(Serializer.LONG).valueSerializer(Serializer.LONG).makeOrGet();
	// }

	protected void pumpNFlushNClearIndices() {

		Neo4jProcessor.LOG.debug("start pumping indices");

		copyNFlushNClearIndex(tempResourcesIndex, resources, GraphStatics.URI);
		copyNFlushNClearIndex(tempResourcesWDataModelIndex, resourcesWDataModel, GraphStatics.URI);
		copyNFlushNClearIndex(tempResourceTypes, resourceTypes, GraphStatics.URI);
		copyLongIndex(tempStatementHashes, statementHashes, GraphStatics.HASH);

		Neo4jProcessor.LOG.debug("finished pumping indices");
	}

	private void copyNFlushNClearIndex(final ObjectLongMap<String> tempIndex, final BatchInserterIndex neo4jIndex, final String indexProperty) {

		Neo4jProcessor.LOG.debug("start pumping index");

		for (final ObjectLongCursor<String> entry : tempIndex) {

			// mapdbIndex.put(entry.key.toCharArray(), entry.value);
			neo4jIndex.add(entry.value, MapUtil.map(indexProperty, entry.key.toCharArray()));
		}

		Neo4jProcessor.LOG.debug("finished pumping index");

		Neo4jProcessor.LOG.debug("start flushing and clearing index");

		neo4jIndex.flush();
		tempIndex.clear();

		Neo4jProcessor.LOG.debug("finished flushing and clearing index");
	}

	private void copyLongIndex(final LongLongMap tempIndex, final BatchInserterIndex neo4jIndex, final String indexProperty) {

		Neo4jProcessor.LOG.debug("start pumping index");

		for (final LongLongCursor entry : tempIndex) {

			// mapdbIndex.put(entry.key, entry.value);
			neo4jIndex.add(entry.value, MapUtil.map(indexProperty, entry.key));
		}

		Neo4jProcessor.LOG.debug("finished pumping index");

		Neo4jProcessor.LOG.debug("start flushing and clearing index");

		neo4jIndex.flush();
		tempIndex.clear();

		Neo4jProcessor.LOG.debug("finished flushing and clearing index");
	}

	// protected void initSnapshotMapDBIndices() {
	//
	// snapshotMapdbResourcesIndex =
	// snapshotMapdb.createHashMap("resources").keySerializer(Serializer.CHAR_ARRAY).valueSerializer(Serializer.LONG).makeOrGet();
	// snapshotMapdbResourcesWDataModelIndex =
	// snapshotMapdb.createHashMap("resources_w_data_model").keySerializer(Serializer.CHAR_ARRAY).valueSerializer(Serializer.LONG).makeOrGet();
	// snapshotMapdbResourcesTypesIndex =
	// snapshotMapdb.createHashMap("resource_types").keySerializer(Serializer.CHAR_ARRAY).valueSerializer(Serializer.LONG).makeOrGet();
	// snapshotMapdbStatementHashesIndex =
	// snapshotMapdb.createHashMap("statement_hashes").keySerializer(Serializer.LONG).valueSerializer(Serializer.LONG).makeOrGet();
	// }

	protected void initIndices() throws DMPGraphException {

		try {

			resources = getOrCreateIndex("resources", GraphStatics.URI, true);
			resourcesWDataModel = getOrCreateIndex("resources_w_data_model", GraphStatics.URI_W_DATA_MODEL, true);
			resourceTypes = getOrCreateIndex("resource_types", GraphStatics.URI, true);
			values = getOrCreateIndex("values", GraphStatics.VALUE, true);
			statementHashes = getOrCreateIndex("statement_hashes", GraphStatics.HASH, false);
		} catch (final Exception e) {

			final String message = "couldn't load indices successfully";

			Neo4jProcessor.LOG.error(message, e);
			Neo4jProcessor.LOG.debug("couldn't finish writing successfully");

			throw new DMPGraphException(message);
		}
	}

	public BatchInserter getBatchInserter() {

		return inserter;
	}

	// public BatchInserterIndex getResourcesIndex() {
	//
	// return resources;
	// }

	public void addToResourcesIndex(final String key, final Long nodeId) {

		// resources.add(nodeId, MapUtil.map(GraphStatics.URI, key));
		tempResourcesIndex.put(key, nodeId);
		// addToIndex(key, nodeId, GraphStatics.URI, resources, tempResourcesIndex, mapdbResourcesIndex);
	}

	public Optional<Long> getNodeIdFromResourcesIndex(final String key) {

		return getIdFromIndex(key, tempResourcesIndex, resources, GraphStatics.URI);
		// return getIdFromIndex(key, tempResourcesIndex, snapshotMapdbResourcesIndex);
	}

	// public BatchInserterIndex getResourcesWDataModelIndex() {
	//
	// return resourcesWDataModel;
	// }

	public void addToResourcesWDataModelIndex(final String key, final Long nodeId) {

		// resourcesWDataModel.add(nodeId, MapUtil.map(GraphStatics.URI_W_DATA_MODEL, key));
		tempResourcesWDataModelIndex.put(key, nodeId);
		// addToIndex(key, nodeId, GraphStatics.URI_W_DATA_MODEL, resourcesWDataModel, tempResourcesWDataModelIndex,
		// mapdbResourcesWDataModelIndex);
	}

	public Optional<Long> getNodeIdFromResourcesWDataModelIndex(final String key) {

		return getIdFromIndex(key, tempResourcesWDataModelIndex, resourcesWDataModel, GraphStatics.URI_W_DATA_MODEL);
		// return getIdFromIndex(key, tempResourcesWDataModelIndex, snapshotMapdbResourcesWDataModelIndex);
	}

	//
	// public ObjectLongMap<String> getBNodesIndex() {
	//
	// return bnodes;
	// }

	public void addToBNodesIndex(final String key, final Long nodeId) {

		bnodes.put(key, nodeId);
	}

	public Optional<Long> getNodeIdFromBNodesIndex(final String key) {

		if (key == null) {

			return Optional.absent();
		}

		if (bnodes.containsKey(key)) {

			return Optional.fromNullable(bnodes.get(key));
		}

		return Optional.absent();
	}

	//
	// public BatchInserterIndex getResourceTypesIndex() {
	//
	// return resourceTypes;
	// }

	public void addToResourceTypesIndex(final String key, final Long nodeId) {

		// resourceTypes.add(nodeId, MapUtil.map(GraphStatics.URI, key));
		tempResourceTypes.put(key, nodeId);
		// addToIndex(key, nodeId, GraphStatics.URI, resourceTypes, tempResourceTypes, mapdbResourcesTypesIndex);
	}

	public Optional<Long> getNodeIdFromResourceTypesIndex(final String key) {

		return getIdFromIndex(key, tempResourceTypes, resourceTypes, GraphStatics.URI);
		// return getIdFromIndex(key, tempResourceTypes, snapshotMapdbResourcesTypesIndex);
	}

	//
	// public BatchInserterIndex getValueIndex() {
	//
	// return values;
	// }

	public void addToValueIndex(final String key, final Long nodeId) {

		values.add(nodeId, MapUtil.map(GraphStatics.VALUE, key));
	}

	//
	// public BatchInserterIndex getStatementIndex() {
	//
	// return statementHashes;
	// }

	public void addToStatementIndex(final long key, final Long nodeId) {

		// statementHashes.add(nodeId, MapUtil.map(GraphStatics.HASH, key));
		tempStatementHashes.put(key, nodeId);
		// addToLongIndex(key, nodeId, GraphStatics.HASH, statementHashes, tempStatementHashes, mapdbStatementHashesIndex);
	}

	//
	// public LongObjectMap<String> getNodeResourceMap() {
	//
	// return nodeResourceMap;
	// }

	public void flushIndices() throws DMPGraphException {

		Neo4jProcessor.LOG.debug("start flushing indices");

		if (resources == null) {

			initIndices();
		}

		pumpNFlushNClearIndices();
		// renewMapDB();
		// resources.flush();
		// resourcesWDataModel.flush();
		// resourceTypes.flush();
		flushStatementIndices();
		clearTempIndices();

		Neo4jProcessor.LOG.debug("start finished flushing indices");
	}

	// protected void renewMapDB() {
	//
	// pumpNFlushNClearIndices();
	// mapdb.commit();
	// mapdbResourcesIndex.close();
	// mapdbResourcesWDataModelIndex.close();
	// mapdbResourcesTypesIndex.close();
	// mapdbStatementHashesIndex.close();
	//
	// if(snapshotMapdb != null) {
	//
	// snapshotMapdbResourcesIndex.close();
	// snapshotMapdbResourcesWDataModelIndex.close();
	// snapshotMapdbResourcesTypesIndex.close();
	// snapshotMapdbStatementHashesIndex.close();
	// snapshotMapdb.close();
	// }
	//
	// mapdb.close();
	// mapdb = tx.makeTx();
	//
	// snapshotMapdb = mapdb.snapshot();
	// initSnapshotMapDBIndices();
	//
	// initMapDBIndices();
	// }
	//
	// public void closeMapDB() {
	//
	// mapdb.commit();
	// mapdb.close();
	// tx.close();
	// }

	public void flushStatementIndices() {

		// statementHashes.flush();
	}

	protected void clearTempIndices() {

		// tempResourcesIndex.clear();
		// tempResourcesWDataModelIndex.clear();
		// tempResourceTypes.clear();
		clearTempStatementIndices();
	}

	protected void clearTempStatementIndices() {

		tempStatementHashes.clear();
	}

	public void clearMaps() {

		nodeResourceMap.clear();
		bnodes.clear();
	}

	public Optional<Long> determineNode(final Optional<NodeType> optionalResourceNodeType, final Optional<String> optionalResourceId,
			final Optional<String> optionalResourceURI, final Optional<String> optionalDataModelURI) {

		if (!optionalResourceNodeType.isPresent()) {

			return Optional.absent();
		}

		if (NodeType.Resource.equals(optionalResourceNodeType.get()) || NodeType.TypeResource.equals(optionalResourceNodeType.get())) {

			// resource node

			final Optional<Long> optionalNodeId;

			if (!NodeType.TypeResource.equals(optionalResourceNodeType.get())) {

				if (!optionalDataModelURI.isPresent()) {

					optionalNodeId = getResourceNodeHits(optionalResourceURI.get());
				} else {

					optionalNodeId = getNodeIdFromResourcesWDataModelIndex(optionalResourceURI.get() + optionalDataModelURI.get());
				}
			} else {

				optionalNodeId = getNodeIdFromResourceTypesIndex(optionalResourceURI.get());
			}

			return optionalNodeId;
		}

		if (NodeType.Literal.equals(optionalResourceNodeType.get())) {

			// literal node - should never be the case

			return Optional.absent();
		}

		// resource must be a blank node

		return getNodeIdFromBNodesIndex(optionalResourceId.get());
	}

	public Optional<String> determineResourceUri(final long subjectNodeId, final Optional<NodeType> optionalSubjectNodeType,
			final Optional<String> optionalSubjectURI, final Optional<String> optionalResourceURI) {

		final Optional<String> optionalResourceUri;

		if (nodeResourceMap.containsKey(subjectNodeId)) {

			optionalResourceUri = Optional.of(nodeResourceMap.get(subjectNodeId));
		} else {

			optionalResourceUri = determineResourceUri(optionalSubjectNodeType, optionalSubjectURI, optionalResourceURI);

			if (optionalResourceUri.isPresent()) {

				nodeResourceMap.put(subjectNodeId, optionalResourceUri.get());
			}
		}

		return optionalResourceUri;
	}

	public Optional<String> determineResourceUri(final Optional<NodeType> optionalSubjectNodeType, final Optional<String> optionalSubjectURI,
			final Optional<String> optionalResourceURI) {

		final Optional<String> optionalResourceUri;

		if (optionalSubjectNodeType.isPresent()
				&& (NodeType.Resource.equals(optionalSubjectNodeType.get()) || NodeType.TypeResource.equals(optionalSubjectNodeType.get()))) {

			optionalResourceUri = optionalSubjectURI;
		} else if (optionalResourceURI.isPresent()) {

			optionalResourceUri = optionalResourceURI;
		} else {

			// shouldn't never be the case

			return Optional.absent();
		}

		return optionalResourceUri;
	}

	public void addLabel(final Long nodeId, final String labelString) {

		final Label label = DynamicLabel.label(labelString);

		inserter.setNodeLabels(nodeId, label);
	}

	public Optional<Long> getStatement(final long hash) throws DMPGraphException {

		return getIdFromLongIndex(hash, tempStatementHashes, statementHashes, GraphStatics.HASH);
		// return getIdFromLongIndex(hash, tempStatementHashes, snapshotMapdbStatementHashesIndex);
	}

	public Map<String, Object> prepareRelationship(final Long subjectNodeId, final String predicateURI, final Long objectNodeId, final String statementUUID,
			final Optional<Map<String, Object>> optionalQualifiedAttributes) {

		//final RelationshipType relType = DynamicRelationshipType.withName(predicateURI);

		final Map<String, Object> relProperties = new HashMap<>();

		relProperties.put(GraphStatics.UUID_PROPERTY, statementUUID);

		if (optionalQualifiedAttributes.isPresent()) {

			final Map<String, Object> qualifiedAttributes = optionalQualifiedAttributes.get();

			if (qualifiedAttributes.containsKey(GraphStatics.ORDER_PROPERTY)) {

				relProperties.put(GraphStatics.ORDER_PROPERTY, qualifiedAttributes.get(GraphStatics.ORDER_PROPERTY));
			}

			if (qualifiedAttributes.containsKey(GraphStatics.INDEX_PROPERTY)) {

				relProperties.put(GraphStatics.INDEX_PROPERTY, qualifiedAttributes.get(GraphStatics.INDEX_PROPERTY));
			}

			// TODO: versioning handling only implemented for data models right now

			if (qualifiedAttributes.containsKey(GraphStatics.EVIDENCE_PROPERTY)) {

				relProperties.put(GraphStatics.EVIDENCE_PROPERTY, qualifiedAttributes.get(GraphStatics.EVIDENCE_PROPERTY));
			}

			if (qualifiedAttributes.containsKey(GraphStatics.CONFIDENCE_PROPERTY)) {

				relProperties.put(GraphStatics.CONFIDENCE_PROPERTY, qualifiedAttributes.get(GraphStatics.CONFIDENCE_PROPERTY));
			}
		}

		return relProperties;
	}

	public long generateStatementHash(final Long subjectNodeId, final String predicateName, final Long objectNodeId, final NodeType subjectNodeType,
			final NodeType objectNodeType) throws DMPGraphException {

		final Optional<NodeType> optionalSubjectNodeType = Optional.fromNullable(subjectNodeType);
		final Optional<NodeType> optionalObjectNodeType = Optional.fromNullable(objectNodeType);
		final Optional<String> optionalSubjectIdentifier = getIdentifier(subjectNodeId, optionalSubjectNodeType);
		final Optional<String> optionalObjectIdentifier = getIdentifier(objectNodeId, optionalObjectNodeType);

		return generateStatementHash(predicateName, optionalSubjectNodeType, optionalObjectNodeType, optionalSubjectIdentifier,
				optionalObjectIdentifier);
	}

	public long generateStatementHash(final Long subjectNodeId, final String predicateName, final String objectValue, final NodeType subjectNodeType,
			final NodeType objectNodeType) throws DMPGraphException {

		final Optional<NodeType> optionalSubjectNodeType = Optional.fromNullable(subjectNodeType);
		final Optional<NodeType> optionalObjectNodeType = Optional.fromNullable(objectNodeType);
		final Optional<String> optionalSubjectIdentifier = getIdentifier(subjectNodeId, optionalSubjectNodeType);
		final Optional<String> optionalObjectIdentifier = Optional.fromNullable(objectValue);

		return generateStatementHash(predicateName, optionalSubjectNodeType, optionalObjectNodeType, optionalSubjectIdentifier,
				optionalObjectIdentifier);
	}

	public long generateStatementHash(final String predicateName, final Optional<NodeType> optionalSubjectNodeType,
			final Optional<NodeType> optionalObjectNodeType, final Optional<String> optionalSubjectIdentifier,
			final Optional<String> optionalObjectIdentifier) throws DMPGraphException {

		if (!optionalSubjectNodeType.isPresent() || !optionalObjectNodeType.isPresent() || !optionalSubjectIdentifier.isPresent()
				|| !optionalObjectIdentifier.isPresent()) {

			final String message = "cannot generate statement hash, because the subject node type or object node type or subject identifier or object identifier is not present";

			Neo4jProcessor.LOG.error(message);

			throw new DMPGraphException(message);
		}

		final String hashString = optionalSubjectNodeType.toString() + ":" + optionalSubjectIdentifier.get() + " " + predicateName + " "
				+ optionalObjectNodeType.toString() + ":" + optionalObjectIdentifier.get() + " ";

		// MessageDigest messageDigest = null;
		//
		// try {
		//
		// messageDigest = MessageDigest.getInstance("SHA-256");
		// } catch (final NoSuchAlgorithmException e) {
		//
		// throw new DMPGraphException("couldn't instantiate hash algo");
		// }
		// messageDigest.update(sb.toString().getBytes());
		//
		// return new String(messageDigest.digest());

		return SipHash.digest(Neo4jProcessor.SPEC_KEY, hashString.getBytes(Charsets.UTF_8));
	}

	public Optional<String> getIdentifier(final Long nodeId, final Optional<NodeType> optionalNodeType) {

		if (!optionalNodeType.isPresent()) {

			return Optional.absent();
		}

		final String identifier;

		switch (optionalNodeType.get()) {

			case Resource:
			case TypeResource:

				final String uri = (String) getProperty(GraphStatics.URI_PROPERTY, inserter.getNodeProperties(nodeId));
				final String dataModel = (String) getProperty(GraphStatics.DATA_MODEL_PROPERTY, inserter.getNodeProperties(nodeId));

				if (dataModel == null) {

					identifier = uri;
				} else {

					identifier = uri + dataModel;
				}

				break;
			case BNode:
			case TypeBNode:

				identifier = "" + nodeId;

				break;
			case Literal:

				identifier = (String) getProperty(GraphStatics.VALUE_PROPERTY, inserter.getNodeProperties(nodeId));

				break;
			default:

				identifier = null;

				break;
		}

		return Optional.fromNullable(identifier);
	}

	public abstract void addObjectToResourceWDataModelIndex(final Long nodeId, final String URI, final Optional<String> optionalDataModelURI);

	public abstract void handleObjectDataModel(final Map<String, Object> objectNodeProperties, final Optional<String> optionalDataModelURI);

	public abstract void handleSubjectDataModel(final Map<String, Object> subjectNodeProperties, String URI,
			final Optional<String> optionalDataModelURI);

	public abstract void addStatementToIndex(final Long relId, final String statementUUID);

	public abstract Optional<Long> getResourceNodeHits(final String resourceURI);

	protected BatchInserterIndex getOrCreateIndex(final String name, final String property, final boolean nodeIndex) {

		final BatchInserterIndexProvider indexProvider = new LuceneBatchInserterIndexProvider(inserter);
		final BatchInserterIndex index;

		if (nodeIndex) {

			index = indexProvider.nodeIndex(name, MapUtil.stringMap("type", "exact"));
		} else {

			index = indexProvider.relationshipIndex(name, MapUtil.stringMap("type", "exact"));
		}

		index.setCacheCapacity(property, 1);

		return index;
	}

	private Object getProperty(final String key, final Map<String, Object> properties) {

		if (properties == null || properties.isEmpty()) {

			return null;
		}

		if (!properties.containsKey(key)) {

			return null;
		}

		return properties.get(key);
	}

	private Optional<Long> getIdFromIndex(final String key, final ObjectLongMap<String> tempIndex, final HTreeMap<char[], Long> mapdbIndex) {

		if (key == null) {

			return Optional.absent();
		}

		if (tempIndex.containsKey(key)) {

			return Optional.of(tempIndex.get(key));
		}

		if (mapdbIndex != null && mapdbIndex.containsKey(key)) {

			final Long hit = mapdbIndex.get(key);

			final Optional<Long> optionalHit = Optional.fromNullable(hit);

			if (optionalHit.isPresent()) {

				// temp cache index hits again
				tempIndex.put(key, optionalHit.get());
			}

			return optionalHit;
		}

		return Optional.absent();
	}

	private Optional<Long> getIdFromLongIndex(final long key, final LongLongMap tempIndex, final HTreeMap<Long, Long> mapdbIndex) {

		if (tempIndex.containsKey(key)) {

			return Optional.of(tempIndex.get(key));
		}

		if (mapdbIndex != null && mapdbIndex.containsKey(key)) {

			final Long hit = mapdbIndex.get(key);

			final Optional<Long> optionalHit = Optional.fromNullable(hit);

			if (optionalHit.isPresent()) {

				// temp cache index hits again
				tempIndex.put(key, optionalHit.get());
			}

			return optionalHit;
		}

		return Optional.absent();
	}

	private Optional<Long> getIdFromIndex(final String key, final ObjectLongMap<String> tempIndex, final BatchInserterIndex index,
			final String indexProperty) {

		if (key == null) {

			return Optional.absent();
		}

		if (tempIndex.containsKey(key)) {

			return Optional.of(tempIndex.get(key));
		}

		if (index == null) {

			return Optional.absent();
		}

		final IndexHits<Long> hits = index.get(indexProperty, key);

		if (hits != null && hits.hasNext()) {

			final Long hit = hits.next();

			hits.close();

			final Optional<Long> optionalHit = Optional.fromNullable(hit);

			if (optionalHit.isPresent()) {

				// temp cache index hits again
				tempIndex.put(key, optionalHit.get());
			}

			return optionalHit;
		}

		if (hits != null) {

			hits.close();
		}

		return Optional.absent();
	}

	private Optional<Long> getIdFromLongIndex(final long key, final LongLongMap tempIndex, final BatchInserterIndex index, final String indexProperty) {

		if (tempIndex.containsKey(key)) {

			return Optional.of(tempIndex.get(key));
		}

		if (index == null) {

			return Optional.absent();
		}

		final IndexHits<Long> hits = index.get(indexProperty, key);

		if (hits != null && hits.hasNext()) {

			final Long hit = hits.next();

			hits.close();

			final Optional<Long> optionalHit = Optional.fromNullable(hit);

			if (optionalHit.isPresent()) {

				// temp cache index hits again
				tempIndex.put(key, optionalHit.get());
			}

			return optionalHit;
		}

		if (hits != null) {

			hits.close();
		}

		return Optional.absent();
	}

	private void addToIndex(final String key, final Long value, final String indexProperty, final BatchInserterIndex neo4jIndex,
			final ObjectLongMap<String> tempIndex, final HTreeMap<char[], Long> mapdbIndex) {

		// neo4jIndex.add(value, MapUtil.map(indexProperty, key));
		tempIndex.put(key, value);
		// mapdbIndex.put(key.toCharArray(), value);
	}

	private void addToLongIndex(final long key, final long value, final String indexProperty, final BatchInserterIndex neo4jIndex,
			final LongLongMap tempIndex, final HTreeMap<Long, Long> mapdbIndex) {

		// neo4jIndex.add(value, MapUtil.map(indexProperty, key));
		tempIndex.put(key, value);
		// mapdbIndex.put(key, value);
	}
}
