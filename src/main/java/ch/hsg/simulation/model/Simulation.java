package ch.hsg.simulation.model;

import ch.hsg.simulation.api.Component;
import ch.hsg.simulation.event.EventType;
import ch.hsg.simulation.event.SimulationEvent;
import ch.hsg.simulation.execution.StateChange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The model of one simulation: components, their connections and state, and entities. It does
 * not schedule anything or run threads; the engine is the only caller of {@link #apply} and
 * {@link #setStatus}. Assembly methods are only allowed while the simulation is CREATED and not
 * yet sealed by the engine.
 */
public final class Simulation {

    private final UUID id;
    private final String definitionId;
    private final String name;
    private SimulationStatus status = SimulationStatus.CREATED;
    private boolean sealed;

    private final Map<String, Component> components = new LinkedHashMap<>();
    private final Map<String, Set<String>> connections = new LinkedHashMap<>();
    private Map<String, ComponentState> componentStates = new LinkedHashMap<>();
    private Map<String, Entity> entities = new LinkedHashMap<>();

    public Simulation(UUID id, String definitionId, String name) {
        this.id = Objects.requireNonNull(id, "id");
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
        this.name = Objects.requireNonNull(name, "name");
    }

    // ---- assembly (used by SimulationDefinition.create) ----

    public Simulation addComponent(Component component) {
        requireAssembling();
        if (components.containsKey(component.getId())) {
            throw new IllegalArgumentException("Duplicate component id: " + component.getId());
        }
        components.put(component.getId(), component);
        componentStates.put(component.getId(), component.initialState());
        return this;
    }

    public Simulation addEntity(Entity entity) {
        requireAssembling();
        if (entities.containsKey(entity.getId())) {
            throw new IllegalArgumentException("Duplicate entity id: " + entity.getId());
        }
        entities.put(entity.getId(), entity);
        return this;
    }

    /** Directed connection: entities may move from {@code fromId} to {@code toId}. */
    public Simulation connect(String fromId, String toId) {
        requireAssembling();
        connections.computeIfAbsent(fromId, key -> new LinkedHashSet<>()).add(toId);
        return this;
    }

    public Simulation connectBothWays(String firstId, String secondId) {
        return connect(firstId, secondId).connect(secondId, firstId);
    }

    /** Checks references and capacities. Called by the manager before an engine is created. */
    public void validate() {
        if (components.isEmpty()) {
            throw new IllegalStateException("Simulation " + definitionId + " has no components");
        }
        connections.forEach((from, targets) -> {
            requireKnown(from, "Connection source");
            targets.forEach(target -> requireKnown(target, "Connection target"));
        });
        for (Entity entity : entities.values()) {
            entity.getLocationComponentId().ifPresent(location -> requireKnown(location, "Location of entity " + entity.getId()));
        }
        for (Component component : components.values()) {
            long count = countAt(entities, component.getId(), null);
            if (count > component.getCapacity()) {
                throw new IllegalStateException("Component " + component.getId() + " exceeds its capacity initially");
            }
        }
    }

    /** Prevents further assembly; called by the engine. */
    public void seal() {
        this.sealed = true;
    }

    // ---- queries ----

    public UUID getId() {
        return id;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public String getName() {
        return name;
    }

    public SimulationStatus getStatus() {
        return status;
    }

    /** Engine only. */
    public void setStatus(SimulationStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public Optional<Component> findComponent(String componentId) {
        return Optional.ofNullable(components.get(componentId));
    }

    public Collection<Component> getComponents() {
        return Collections.unmodifiableCollection(components.values());
    }

    public ComponentState stateOf(String componentId) {
        ComponentState state = componentStates.get(componentId);
        if (state == null) {
            throw new NotFoundException("Unknown component: " + componentId);
        }
        return state;
    }

    public Optional<EntityView> findEntity(String entityId) {
        return Optional.ofNullable(entities.get(entityId)).map(Entity::view);
    }

    public List<EntityView> getEntities() {
        return entities.values().stream().map(Entity::view).toList();
    }

    public List<EntityView> entitiesAt(String componentId) {
        return entities.values().stream()
                .filter(entity -> entity.getLocationComponentId().filter(componentId::equals).isPresent())
                .map(Entity::view)
                .toList();
    }

    public List<Connection> getConnections() {
        List<Connection> result = new ArrayList<>();
        connections.forEach((from, targets) -> targets.forEach(to -> result.add(new Connection(from, to))));
        return result;
    }

    public boolean isConnected(String fromId, String toId) {
        return connections.getOrDefault(fromId, Set.of()).contains(toId);
    }

    // ---- mutation (engine only) ----

    /**
     * Validates and applies a batch of changes atomically. On any violation nothing is applied and
     * an {@link InvalidStateChangeException} is thrown. Returns draft events describing the
     * applied changes.
     */
    public List<SimulationEvent> apply(List<StateChange> changes) {
        Map<String, Entity> nextEntities = new LinkedHashMap<>();
        entities.forEach((entityId, entity) -> nextEntities.put(entityId, entity.copy()));
        Map<String, ComponentState> nextStates = new LinkedHashMap<>(componentStates);
        List<SimulationEvent> events = new ArrayList<>();

        for (StateChange change : changes) {
            switch (change) {
                case StateChange.CreateEntity create -> {
                    if (nextEntities.containsKey(create.entityId())) {
                        throw new InvalidStateChangeException("Entity already exists: " + create.entityId());
                    }
                    if (create.locationComponentId() != null) {
                        requireComponentForChange(create.locationComponentId());
                        requireCapacity(nextEntities, create.locationComponentId(), null);
                    }
                    nextEntities.put(create.entityId(),
                            new Entity(create.entityId(), create.type(), create.attributes(), create.locationComponentId()));
                    events.add(entityEvent(EventType.ENTITY_CREATED, create.locationComponentId(), create.entityId(),
                            payload("type", create.type(), "location", create.locationComponentId())));
                }
                case StateChange.RemoveEntity remove -> {
                    Entity entity = requireEntity(nextEntities, remove.entityId());
                    nextEntities.remove(remove.entityId());
                    events.add(entityEvent(EventType.ENTITY_REMOVED, entity.getLocationComponentId().orElse(null),
                            remove.entityId(), Map.of()));
                }
                case StateChange.MoveEntity move -> {
                    Entity entity = requireEntity(nextEntities, move.entityId());
                    requireComponentForChange(move.targetComponentId());
                    String from = entity.getLocationComponentId().orElse(null);
                    if (move.targetComponentId().equals(from)) {
                        continue;
                    }
                    if (from != null && !isConnected(from, move.targetComponentId())) {
                        throw new InvalidStateChangeException("No connection from " + from + " to " + move.targetComponentId());
                    }
                    requireCapacity(nextEntities, move.targetComponentId(), move.entityId());
                    entity.moveTo(move.targetComponentId());
                    events.add(entityEvent(EventType.ENTITY_MOVED, move.targetComponentId(), move.entityId(),
                            payload("from", from, "to", move.targetComponentId())));
                }
                case StateChange.UpdateEntityAttributes update -> {
                    Entity entity = requireEntity(nextEntities, update.entityId());
                    entity.updateAttributes(update.attributes());
                    events.add(entityEvent(EventType.ENTITY_UPDATED, entity.getLocationComponentId().orElse(null),
                            update.entityId(), update.attributes()));
                }
                case StateChange.UpdateComponentState update -> {
                    requireComponentForChange(update.componentId());
                    ComponentState previous = nextStates.put(update.componentId(), update.state());
                    if (!update.state().equals(previous)) {
                        events.add(SimulationEvent.draft(EventType.COMPONENT_STATE_CHANGED, update.componentId(),
                                update.state().attributes()));
                    }
                }
            }
        }
        this.entities = nextEntities;
        this.componentStates = nextStates;
        return events;
    }

    private void requireAssembling() {
        if (sealed || status != SimulationStatus.CREATED) {
            throw new SimulationStateException("Simulation " + id + " can no longer be assembled");
        }
    }

    private void requireKnown(String componentId, String what) {
        if (!components.containsKey(componentId)) {
            throw new IllegalStateException(what + " references unknown component " + componentId);
        }
    }

    private void requireComponentForChange(String componentId) {
        if (!components.containsKey(componentId)) {
            throw new InvalidStateChangeException("Unknown component: " + componentId);
        }
    }

    private static Entity requireEntity(Map<String, Entity> entities, String entityId) {
        Entity entity = entities.get(entityId);
        if (entity == null) {
            throw new InvalidStateChangeException("Unknown entity: " + entityId);
        }
        return entity;
    }

    private void requireCapacity(Map<String, Entity> entities, String componentId, String movingEntityId) {
        int capacity = components.get(componentId).getCapacity();
        if (countAt(entities, componentId, movingEntityId) >= capacity) {
            throw new InvalidStateChangeException("Component " + componentId + " is full (capacity " + capacity + ")");
        }
    }

    private static long countAt(Map<String, Entity> entities, String componentId, String excludedEntityId) {
        return entities.values().stream()
                .filter(entity -> !entity.getId().equals(excludedEntityId))
                .filter(entity -> entity.getLocationComponentId().filter(componentId::equals).isPresent())
                .count();
    }

    private static SimulationEvent entityEvent(EventType type, String source, String entityId, Map<String, ?> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityId", entityId);
        payload.putAll(details);
        return SimulationEvent.draft(type, source, payload);
    }

    private static Map<String, Object> payload(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key1, value1);
        payload.put(key2, value2);
        return payload;
    }
}
