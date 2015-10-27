package org.grails.datastore.gorm.neo4j.engine;

import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.grails.datastore.gorm.neo4j.*;
import org.grails.datastore.gorm.neo4j.collection.*;
import org.grails.datastore.gorm.neo4j.mapping.reflect.Neo4jNameUtils;
import org.grails.datastore.mapping.collection.PersistentCollection;
import org.grails.datastore.mapping.core.Session;
import org.grails.datastore.mapping.core.impl.*;
import org.grails.datastore.mapping.dirty.checking.DirtyCheckable;
import org.grails.datastore.mapping.dirty.checking.DirtyCheckableCollection;
import org.grails.datastore.mapping.engine.EntityAccess;
import org.grails.datastore.mapping.engine.EntityPersister;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.config.GormProperties;
import org.grails.datastore.mapping.model.types.*;
import org.grails.datastore.mapping.query.Query;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.helpers.collection.IteratorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import javax.persistence.CascadeType;
import java.io.Serializable;
import java.util.*;

import static org.grails.datastore.mapping.query.Query.*;

/**
 * @author Stefan Armbruster (stefan@armbruster-it.de)
 */
public class Neo4jEntityPersister extends EntityPersister {

    public static final String DELETE_ONE_CYPHER = "MATCH (n%s) WHERE n.__id__={id} OPTIONAL MATCH (n)-[r]-() DELETE r,n";
    public static final String DELETE_MANY_CYPHER = "MATCH (n%s) WHERE n.__id__ in {id} OPTIONAL MATCH (n)-[r]-() DELETE r,n";
    public static final String DYNAMIC_ASSOCIATIONS_QUERY = "MATCH (m%s {__id__:{id}})-[r]-(o) RETURN type(r) as relType, startNode(r)=m as out, {ids: collect(o.__id__), labels: collect(labels(o))} as values";

    private static Logger log = LoggerFactory.getLogger(Neo4jEntityPersister.class);


    public Neo4jEntityPersister(MappingContext mappingContext, PersistentEntity entity, Session session, ApplicationEventPublisher publisher) {
        super(mappingContext, entity, session, publisher);
    }

    @Override
    public Neo4jSession getSession() {
        return (Neo4jSession) super.session;
    }

    @Override
    protected List<Object> retrieveAllEntities(PersistentEntity pe, Serializable[] keys) {
        return retrieveAllEntities(pe, Arrays.asList(keys));
    }

    @Override
    protected List<Object> retrieveAllEntities(PersistentEntity pe, Iterable<Serializable> keys) {
        List<Criterion> criterions = new ArrayList<Criterion>(1);
        criterions.add(new In(GormProperties.IDENTITY, IteratorUtil.asCollection(keys)));
        Junction junction = new Conjunction(criterions);
        return new Neo4jQuery(session, pe, this).executeQuery(pe, junction);
    }

    @Override
    protected List<Serializable> persistEntities(PersistentEntity pe, @SuppressWarnings("rawtypes") Iterable objs) {
        List<Serializable> result = new ArrayList<Serializable>();
        for (Object obj: objs) {
            result.add(persistEntity(pe, obj));
        }
        return result;
    }

    @Override
    protected Object retrieveEntity(PersistentEntity pe, Serializable key) {
        List<Criterion> criteria = new ArrayList<Criterion>(1);
        criteria.add(new IdEquals(key));
        return IteratorUtil.singleOrNull(new Neo4jQuery(session, pe, this).executeQuery(pe, new Conjunction(criteria)).iterator());
    }

    public Object unmarshallOrFromCache(PersistentEntity defaultPersistentEntity, Map<String, Object> resultData) {

        Node data = (Node)resultData.get(CypherBuilder.NODE_DATA);
        return unmarshallOrFromCache(defaultPersistentEntity, data, resultData);
    }

    protected Object unmarshallOrFromCache(PersistentEntity defaultPersistentEntity, Node data) {
        return unmarshallOrFromCache(defaultPersistentEntity, data, Collections.<String,Object>emptyMap());
    }

    protected Object unmarshallOrFromCache(PersistentEntity defaultPersistentEntity, Node data, Map<String, Object> resultData) {
        final Iterable<Label> labels = data.getLabels();
        PersistentEntity persistentEntity = mostSpecificPersistentEntity(defaultPersistentEntity, labels);
        final long id = ((Number) data.getProperty(CypherBuilder.IDENTIFIER)).longValue();
        Object instance = getSession().getCachedInstance(persistentEntity.getJavaClass(), id);

        if (instance == null) {
            instance = unmarshall(persistentEntity, id, data, resultData);
            getSession().cacheInstance(persistentEntity.getJavaClass(), id, instance);
        }
        return instance;
    }

    private PersistentEntity mostSpecificPersistentEntity(PersistentEntity pe, Iterable<Label> labels) {
        PersistentEntity result = null;
        int longestInheritenceChain = -1;

        for (Label l: labels) {
            PersistentEntity persistentEntity = findDerivedPersistentEntityWithLabel(pe, l.name());
            if (persistentEntity!=null) {
                int inheritenceChain = calcInheritenceChain(persistentEntity);
                if (inheritenceChain > longestInheritenceChain) {
                    longestInheritenceChain = inheritenceChain;
                    result = persistentEntity;
                }
            }
        }
        if(result != null) {
            return result;
        }
        else {
            return pe;
        }
    }

    private PersistentEntity findDerivedPersistentEntityWithLabel(PersistentEntity parent, String label) {
        for (PersistentEntity pe: getMappingContext().getPersistentEntities()) {
            if (isInParentsChain(parent, pe)) {
                if (((GraphPersistentEntity)pe).getLabels().contains(label)) {
                    return pe;
                }
            }
        }
        return null;
    }

    private boolean isInParentsChain(PersistentEntity parent, PersistentEntity it) {
        if (it==null) {
            return false;
        } else if (it.equals(parent)) {
            return true;
        } else return isInParentsChain(parent, it.getParentEntity());
    }

    private int calcInheritenceChain(PersistentEntity current) {
        if (current == null) {
            return 0;
        } else {
            return calcInheritenceChain(current.getParentEntity()) + 1;
        }
    }


    private Object unmarshall(PersistentEntity persistentEntity, Long id, Node node, Map<String, Object> resultData) {

        if(log.isDebugEnabled()) {
            log.debug( "unmarshalling entity [{}] with id [{}], props {}, {}", persistentEntity.getName(), id, node);
        }
        final Neo4jSession session = getSession();
        GraphPersistentEntity graphPersistentEntity = (GraphPersistentEntity) persistentEntity;
        EntityAccess entityAccess = session.createEntityAccess(persistentEntity, persistentEntity.newInstance());
        entityAccess.setIdentifierNoConversion(id);

        Map<TypeDirectionPair, Map<String, Collection>> relationshipsMap = new HashMap<TypeDirectionPair, Map<String, Collection>>();
        final boolean hasDynamicAssociations = graphPersistentEntity.hasDynamicAssociations();
        if(hasDynamicAssociations) {

            final String cypher = String.format(DYNAMIC_ASSOCIATIONS_QUERY, ((GraphPersistentEntity) persistentEntity).getLabelsAsString());
            final Map<String, Object> isMap = Collections.<String, Object>singletonMap(GormProperties.IDENTITY, id);

            final GraphDatabaseService graphDatabaseService = getSession().getNativeInterface();

            if(log.isDebugEnabled()) {
                log.debug("QUERY Cypher [{}] for parameters [{}]", cypher, isMap);
            }

            final Result relationships = graphDatabaseService.execute(cypher, isMap);
            while(relationships.hasNext()) {
                final Map<String, Object> row = relationships.next();
                String relType = (String) row.get("relType");
                Boolean outGoing = (Boolean) row.get("out");
                Map<String, Collection> values = (Map<String, Collection>) row.get("values");
                TypeDirectionPair key = new TypeDirectionPair(relType, outGoing);
                relationshipsMap.put(key, values);
            }
        }


        final List<String> nodeProperties = DefaultGroovyMethods.toList(node.getPropertyKeys());

        for (PersistentProperty property: entityAccess.getPersistentEntity().getPersistentProperties()) {

            String propertyName = property.getName();
            if (property instanceof Simple) {
                // implicitly sets version property as well
                if(node.hasProperty(propertyName)) {

                    entityAccess.setProperty(propertyName, node.getProperty(propertyName));

                    nodeProperties.remove(propertyName);
                }
            } else if (property instanceof Association) {

                Association association = (Association) property;

                if(hasDynamicAssociations) {
                    TypeDirectionPair typeDirectionPair = new TypeDirectionPair(
                            RelationshipUtils.relationshipTypeUsedFor(association),
                            !RelationshipUtils.useReversedMappingFor(association)
                    );
                    relationshipsMap.remove(typeDirectionPair);
                }
                final String associationName = association.getName();
                final String associationNodesKey = associationName + "Nodes";


                // if the node key is present we have an eager fetch, so initialise the association
                if(resultData.containsKey(associationNodesKey)) {
                    if (association instanceof ToOne) {
                        final PersistentEntity associatedEntity = association.getAssociatedEntity();
                        final Neo4jEntityPersister associationPersister = getSession().getEntityPersister(associatedEntity.getJavaClass());
                        final Iterable<Node> associationNodes = (Iterable<Node>) resultData.get(associationNodesKey);
                        final Node associationNode = IteratorUtil.singleOrNull(associationNodes);
                        if(associationNode != null) {
                            entityAccess.setPropertyNoConversion(
                                    association.getName(),
                                    associationPersister.unmarshallOrFromCache(associatedEntity, associationNode)
                            );
                        }
                    }
                    else if(association instanceof ToMany) {
                        Collection values;
                        final Class type = association.getType();
                        final PersistentEntity associatedEntity = association.getAssociatedEntity();
                        final Neo4jEntityPersister associationPersister = getSession().getEntityPersister(associatedEntity.getJavaClass());

                        final Collection<Object> associationNodes = (Collection<Object>) resultData.get(associationNodesKey);
                        final Neo4jResultList resultSet = new Neo4jResultList(0, associationNodes.size(), associationNodes.iterator(), associationPersister);
                        if(List.class.isAssignableFrom(type)) {
                            values = new Neo4jList(entityAccess, association, resultSet, session);
                        }
                        else if(SortedSet.class.isAssignableFrom(type)) {
                            values = new Neo4jSortedSet(entityAccess, association, new TreeSet<Object>(resultSet), session);
                        }
                        else {
                            values = new Neo4jSet(entityAccess, association, new HashSet<Object>(resultSet), session);
                        }
                        entityAccess.setPropertyNoConversion(propertyName, values);
                    }
                }
                else {

                    final String associationIdsKey = associationName + "Ids";
                    final Object associationValues = resultData.get(associationIdsKey);
                    List<Long> targetIds = Collections.emptyList();
                    if(associationValues instanceof Collection) {
                        targetIds = (List<Long>) associationValues;
                    }
                    if (association instanceof ToOne) {
                        ToOne toOne = (ToOne) association;
                        if (!targetIds.isEmpty()) {
                            Long targetId;
                            try {
                                targetId = IteratorUtil.single(targetIds);
                            } catch (NoSuchElementException e) {
                                throw new DataIntegrityViolationException("Single-ended association has more than one associated identifier: " + association);
                            }
                            entityAccess.setPropertyNoConversion(propertyName,
                                    getMappingContext().getProxyFactory().createProxy(
                                            this.session,
                                            toOne.getAssociatedEntity().getJavaClass(),
                                            targetId
                                    )
                            );
                        }
                    } else if (association instanceof ToMany) {

                        Collection values;
                        final Class type = association.getType();
                        if(List.class.isAssignableFrom(type)) {
                            values = new Neo4jPersistentList(targetIds, session, entityAccess, (ToMany) association);
                        }
                        else if(SortedSet.class.isAssignableFrom(type)) {
                            values = new Neo4jPersistentSortedSet(targetIds, session, entityAccess, (ToMany) association);
                        }
                        else {
                            values = new Neo4jPersistentSet(targetIds, session, entityAccess, (ToMany) association);
                        }
                        entityAccess.setPropertyNoConversion(propertyName, values);
                    } else {
                        throw new IllegalArgumentException("association " + associationName + " is of type " + association.getClass().getSuperclass().getName());
                    }
                }


            } else {
                throw new IllegalArgumentException("property " + property.getName() + " is of type " + property.getClass().getSuperclass().getName());

            }
        }

        Map<String,Object> undeclared = new LinkedHashMap<String, Object>();

        // if the relationship map is not empty as this point there are dynamic relationships that need to be loaded as undeclared
        if (!relationshipsMap.isEmpty()) {
            for (Map.Entry<TypeDirectionPair, Map<String,Collection>> entry: relationshipsMap.entrySet()) {

                if (entry.getKey().isOutgoing()) {
                    Iterator<Long> idIter = entry.getValue().get("ids").iterator();
                    Iterator<Collection<String>> labelIter = entry.getValue().get("labels").iterator();

                    Collection values = new ArrayList();
                    while (idIter.hasNext() && labelIter.hasNext()) {
                        Long targetId = idIter.next();
                        Collection<String> labels = labelIter.next();
                        Object proxy = getMappingContext().getProxyFactory().createProxy(
                                this.session,
                                ((Neo4jMappingContext) getMappingContext()).findPersistentEntityForLabels(labels).getJavaClass(),
                                targetId
                        );
                        values.add(proxy);
                    }

                    // for single instances and singular property name do not use an array
                    Object value = (values.size()==1) && isSingular(entry.getKey().getType()) ? IteratorUtil.single(values): values;
                    undeclared.put(entry.getKey().getType(), value);
                }
            }
        }

        if (!nodeProperties.isEmpty()) {
            for (String nodeProperty : nodeProperties) {
                if(!nodeProperty.equals(CypherBuilder.IDENTIFIER)) {
                    undeclared.put(nodeProperty, node.getProperty(nodeProperty));
                }
            }
        }

        final Object obj = entityAccess.getEntity();
        if(!undeclared.isEmpty()) {
            getSession().setAttribute(obj, Neo4jGormEnhancer.UNDECLARED_PROPERTIES, undeclared);
        }

        firePostLoadEvent(entityAccess.getPersistentEntity(), entityAccess);
        return obj;
    }

    private Collection createCollection(Association association) {
        return association.isList() ? new ArrayList() : new HashSet();
    }

    private Collection createDirtyCheckableAwareCollection(PendingOperation<Object, Long> pendingOperation, EntityAccess entityAccess, Association association, Collection delegate) {
        if (delegate==null) {
            delegate = createCollection(association);
        }

        if( !(delegate instanceof DirtyCheckableCollection)) {

            final Object entity = entityAccess.getEntity();
            if(entity instanceof DirtyCheckable) {
                final Neo4jSession session = getSession();
                for( Object o : delegate ) {
                    String relType = RelationshipUtils.relationshipTypeUsedFor(association)                                                                                                       ;
                    final EntityAccess associationAccess = getSession().createEntityAccess(association.getAssociatedEntity(), o);
                    final RelationshipPendingInsert insert = new RelationshipPendingInsert(entityAccess, relType, associationAccess, session.getNativeInterface());
                    pendingOperation.addCascadeOperation(insert);
                }

                delegate = association.isList() ?
                        new Neo4jList(entityAccess, association, (List)delegate, session) :
                        new Neo4jSet(entityAccess, association, (Set)delegate, session);
            }
        }
        else {
            final DirtyCheckableCollection dirtyCheckableCollection = (DirtyCheckableCollection) delegate;
            final Neo4jSession session = getSession();
            if(dirtyCheckableCollection.hasChanged()) {
                for (Object o : ((Iterable)dirtyCheckableCollection)) {
                    String relType = RelationshipUtils.relationshipTypeUsedFor(association)                                                                                                       ;
                    final EntityAccess associationAccess = getSession().createEntityAccess(association.getAssociatedEntity(), o);
                    final RelationshipPendingInsert insert = new RelationshipPendingInsert(entityAccess, relType, associationAccess, session.getNativeInterface());
                    pendingOperation.addCascadeOperation(insert);
                }
            }
        }
        return delegate;
    }

    private boolean isSingular(String key) {
        return Neo4jNameUtils.isSingular(key);
    }

    @Override
    protected Serializable persistEntity(final PersistentEntity pe, Object obj) {
        if (obj == null) {
            throw new IllegalStateException("obj is null");
        }

        boolean isDirty = obj instanceof DirtyCheckable ? ((DirtyCheckable)obj).hasChanged() : true;

        final Neo4jSession session = getSession();
        if (session.isPendingAlready(obj) || (!isDirty)) {
            EntityAccess entityAccess = createEntityAccess(pe, obj);
            return (Serializable) entityAccess.getIdentifier();
        }

        final EntityAccess entityAccess = createEntityAccess(pe, obj);
        Object identifier = entityAccess.getIdentifier();
        if (getMappingContext().getProxyFactory().isProxy(obj)) {
            return (Serializable) identifier;
        }


        session.registerPending(obj);

        // cancel operation if vetoed
        boolean isUpdate = identifier != null;
        if (isUpdate) {

            final PendingUpdateAdapter<Object, Long> pendingUpdate = new PendingUpdateAdapter<Object, Long>(pe, (Long) identifier, obj, entityAccess) {
                @Override
                public void run() {
                    if (cancelUpdate(pe, entityAccess)) {
                        setVetoed(true);
                    }
                }
            };
            pendingUpdate.addCascadeOperation(new PendingOperationAdapter<Object, Long>(pe, (Long) identifier, obj) {
                @Override
                public void run() {
                    firePostUpdateEvent(pe, entityAccess);
                }
            });
            session.addPendingUpdate(pendingUpdate);

            persistAssociationsOfEntity(pendingUpdate, pe, entityAccess, true);

        } else {
            identifier = session.getDatastore().nextIdForType(pe);
            entityAccess.setIdentifier(identifier);
            final PendingInsertAdapter<Object, Long> pendingInsert = new PendingInsertAdapter<Object, Long>(pe, (Long) identifier, obj, entityAccess) {
                @Override
                public void run() {
                    if (cancelInsert(pe, entityAccess)) {
                        setVetoed(true);
                    }
                }
            };
            pendingInsert.addCascadeOperation(new PendingOperationAdapter<Object, Long>(pe, (Long) identifier, obj) {
                @Override
                public void run() {
                    firePostInsertEvent(pe, entityAccess);
                }
            });
            session.addPendingInsert(pendingInsert);
            persistAssociationsOfEntity(pendingInsert,pe, entityAccess, false);

        }

        return (Serializable) identifier;
    }

    private void persistAssociationsOfEntity(PendingOperation<Object, Long> pendingOperation, PersistentEntity pe, EntityAccess entityAccess, boolean isUpdate) {

        Object obj = entityAccess.getEntity();
        DirtyCheckable dirtyCheckable = null;
        if (obj instanceof DirtyCheckable) {
            dirtyCheckable = (DirtyCheckable)obj;
        }

        for (PersistentProperty pp: pe.getAssociations()) {
            if ((!isUpdate) || ((dirtyCheckable!=null) && dirtyCheckable.hasChanged(pp.getName()))) {

                Object propertyValue = entityAccess.getProperty(pp.getName());

                if ((pp instanceof OneToMany) || (pp instanceof ManyToMany)) {
                    Association association = (Association) pp;

                    if (propertyValue!= null) {

                        if(propertyValue instanceof PersistentCollection) {
                            PersistentCollection pc = (PersistentCollection) propertyValue;
                            if(!pc.isInitialized()) continue;
                        }

                        if (association.isBidirectional()) {
                            // Populate other side of bidi
                            for (Object associatedObject: (Iterable)propertyValue) {
                                EntityAccess assocEntityAccess = createEntityAccess(association.getAssociatedEntity(), associatedObject);
                                assocEntityAccess.setProperty(association.getReferencedPropertyName(), obj);
                            }
                        }

                        Collection targets = (Collection) propertyValue;
                        persistEntities(association.getAssociatedEntity(), targets);

                        boolean reversed = RelationshipUtils.useReversedMappingFor(association);

                        if (!reversed) {
                            Collection dcc = createDirtyCheckableAwareCollection(pendingOperation, entityAccess, association, targets);
                            entityAccess.setProperty(association.getName(), dcc);
                        }
                    }
                } else if (pp instanceof ToOne) {
                    if (propertyValue != null) {
                        ToOne to = (ToOne) pp;

                        if (to.isBidirectional()) {  // Populate other side of bidi
                            EntityAccess assocEntityAccess = createEntityAccess(to.getAssociatedEntity(), propertyValue);
                            if (to instanceof OneToOne) {
                                assocEntityAccess.setProperty(to.getReferencedPropertyName(), obj);
                            } else {
                                Collection collection = (Collection) assocEntityAccess.getProperty(to.getReferencedPropertyName());
                                if (collection == null ) {
                                    collection = new ArrayList();
                                    assocEntityAccess.setProperty(to.getReferencedPropertyName(), collection);
                                }
                                if (!collection.contains(obj)) {
                                    collection.add(obj);
                                }
                            }
                        }

                        persistEntity(to.getAssociatedEntity(), propertyValue);

                        boolean reversed = RelationshipUtils.useReversedMappingFor(to);
                        String relType = RelationshipUtils.relationshipTypeUsedFor(to);

                        if (!reversed) {
                            final EntityAccess assocationAccess = getSession().createEntityAccess(to.getAssociatedEntity(), propertyValue);
                            if (isUpdate) {
                                pendingOperation.addCascadeOperation(new RelationshipPendingDelete(entityAccess, relType, null, getSession().getNativeInterface()));
                            }
                            pendingOperation.addCascadeOperation(new RelationshipPendingInsert(entityAccess, relType,
                                    assocationAccess,
                                    getSession().getNativeInterface()));
                        }

                    }
                } else {
                    throw new IllegalArgumentException("wtf don't know how to handle " + pp + "(" + pp.getClass() +")" );

                }
            }


        }
    }

    @Override
    protected void deleteEntity(PersistentEntity pe, Object obj) {
        final EntityAccess entityAccess = createEntityAccess(pe, obj);


        final Neo4jSession session = getSession();
        session.clear(obj);
        final PendingDeleteAdapter pendingDelete = createPendingDeleteOne(session, pe, entityAccess, obj);
        session.addPendingDelete(pendingDelete);

        for (Association association: pe.getAssociations()) {
            if (association.isOwningSide() && association.doesCascade(CascadeType.REMOVE)) {
                if(log.isDebugEnabled()) {
                    log.debug("Cascading delete for property {}->{}", association.getType().getName(),  association.getName());
                }

                GraphPersistentEntity otherPersistentEntity = (GraphPersistentEntity) association.getAssociatedEntity();
                Object otherSideValue = entityAccess.getProperty(association.getName());
                if ((association instanceof ToOne) && otherSideValue != null) {
                    // Add cascade operation on parent delete. If parent delete is vetoed, so are child deletes
                    final EntityAccess access = createEntityAccess(otherPersistentEntity, otherSideValue);
                    final Object associationId = access.getIdentifier();
                    if(associationId == null) continue;

                    pendingDelete.addCascadeOperation(
                            createPendingDeleteOne(session, otherPersistentEntity, access, otherSideValue)
                    );
                } else {
                    // TODO: Optimize cascade to associations once lazy loading is properly implemented
                    deleteEntities(otherPersistentEntity, (Iterable) otherSideValue);
                }
            }
        }


    }

    @SuppressWarnings("unchecked")
    private PendingDeleteAdapter createPendingDeleteOne(final Neo4jSession session, final PersistentEntity pe, final EntityAccess entityAccess, final Object obj) {
        return new PendingDeleteAdapter(pe, entityAccess.getIdentifier(), obj) {
            @Override
            public void run() {
                final PersistentEntity e = getEntity();
                if (cancelDelete(e, entityAccess)) {
                    return;
                }
                final String cypher = String.format(DELETE_ONE_CYPHER,
                        ((GraphPersistentEntity) e).getLabelsAsString());
                final Map<String, Object> idMap = Collections.singletonMap(GormProperties.IDENTITY, entityAccess.getIdentifier());

                if (log.isDebugEnabled()) {
                    log.debug("DELETE Cypher [{}] for parameters {}", cypher, idMap);
                }

                final GraphDatabaseService graphDatabaseService = session.getNativeInterface();
                graphDatabaseService.execute(cypher, idMap);

                firePostDeleteEvent(e, entityAccess);
            }
        };
    }



    @Override
    protected void deleteEntities(PersistentEntity pe, @SuppressWarnings("rawtypes") Iterable objects) {
        List<EntityAccess> entityAccesses = new ArrayList<EntityAccess>();
        List<Object> ids = new ArrayList<Object>();
        Map<PersistentEntity, Collection<Object>> cascades = new HashMap<PersistentEntity, Collection<Object>>();

        for (Object obj : objects) {
            EntityAccess entityAccess = createEntityAccess(pe, obj);
            if (cancelDelete(pe, entityAccess)) {
                return;
            }
            entityAccesses.add(entityAccess);
            ids.add(entityAccess.getIdentifier());

            // populate cascades
            for (Association association: pe.getAssociations()) {
                Object property = entityAccess.getProperty(association.getName());

                // TODO: check if property is an empty array -> exclude
                if (association.isOwningSide() && association.doesCascade(CascadeType.REMOVE)) {

                    if (propertyNotEmpty(property)) {

                        PersistentEntity associatedEntity = association.getAssociatedEntity();

                        Collection<Object> cascadesForPersistentEntity = cascades.get(associatedEntity);
                        if (cascadesForPersistentEntity==null) {
                            cascadesForPersistentEntity = new ArrayList<Object>();
                            cascades.put(associatedEntity, cascadesForPersistentEntity);
                        }

                        if (association instanceof ToOne) {
                            cascadesForPersistentEntity.add(property);
                        } else {
                            cascadesForPersistentEntity.addAll((Collection<?>) property);
                        }
                    }
                }
            }
        }

        for (Map.Entry<PersistentEntity, Collection<Object>> entry: cascades.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                deleteEntities(entry.getKey(), entry.getValue());
            }
        }

        final String cypher = String.format(DELETE_MANY_CYPHER,
                ((GraphPersistentEntity) pe).getLabelsAsString());
        final Map<String, Object> params = Collections.<String, Object>singletonMap(GormProperties.IDENTITY, ids);

        if (log.isDebugEnabled()) {
            log.debug("DELETE Cypher [{}] for parameters {}", cypher, params);
        }

        final GraphDatabaseService graphDatabaseService = getSession()
                                                            .getNativeInterface();
        graphDatabaseService
                .execute(cypher, params);

        for (EntityAccess entityAccess: entityAccesses) {
            getSession().clear(entityAccess.getEntity());
            firePostDeleteEvent(pe, entityAccess);
        }
    }

    private boolean propertyNotEmpty(Object property) {
        return property != null && !((property instanceof Collection) && (((Collection) property).isEmpty()));
    }

    @Override
    public Query createQuery() {
        return new Neo4jQuery(session, getPersistentEntity(), this);
    }

    @Override
    public Serializable refresh(Object o) {
        throw new UnsupportedOperationException();
    }
}
