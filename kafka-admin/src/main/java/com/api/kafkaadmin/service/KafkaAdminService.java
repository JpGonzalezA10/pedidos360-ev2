package com.api.kafkaadmin.service;

import com.api.kafkaadmin.config.KafkaAdminProperties;
import com.api.kafkaadmin.dto.AdminResponses.BrokerInfo;
import com.api.kafkaadmin.dto.AdminResponses.ClusterInfo;
import com.api.kafkaadmin.dto.AdminResponses.ConsumerGroupDetail;
import com.api.kafkaadmin.dto.AdminResponses.ConsumerGroupSummary;
import com.api.kafkaadmin.dto.AdminResponses.MemberInfo;
import com.api.kafkaadmin.dto.AdminResponses.OperationResult;
import com.api.kafkaadmin.dto.AdminResponses.PartitionInfo;
import com.api.kafkaadmin.dto.AdminResponses.PartitionLag;
import com.api.kafkaadmin.dto.AdminResponses.TopicDetail;
import com.api.kafkaadmin.dto.AdminResponses.TopicSummary;
import com.api.kafkaadmin.dto.OffsetResetRequest;
import com.api.kafkaadmin.dto.TopicConfigUpdateRequest;
import com.api.kafkaadmin.dto.TopicCreateRequest;
import com.api.kafkaadmin.exception.InvalidConfigurationException;
import com.api.kafkaadmin.exception.ProtectedResourceException;
import com.api.kafkaadmin.exception.ResourceConflictException;
import com.api.kafkaadmin.exception.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListGroupsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/*
 * Logica del administrador de Kafka sobre el AdminClient:
 *  - Cluster: brokers y controlador.
 *  - Topicos: listar, describir (particiones, lider, ISR, offsets, configuracion), crear, modificar
 *    configuracion (retencion, cleanup.policy, min.insync.replicas...), aumentar particiones y eliminar.
 *  - Consumer groups: listar, describir con lag por particion, resetear offsets y eliminar.
 * Las reglas propias (topicos protegidos, claves permitidas, RF <= brokers) se validan aqui antes
 * de llamar al broker; los errores del broker se traducen en KafkaAdminOperations.
 */
@Service
public class KafkaAdminService {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminService.class);
    /** Configuraciones que siempre se muestran en el detalle, aunque tengan el valor por defecto. */
    private static final Set<String> KEY_CONFIGS = Set.of(
            "cleanup.policy", "retention.ms", "retention.bytes", "min.insync.replicas", "segment.ms");

    private final AdminClient admin;
    private final KafkaAdminProperties properties;
    private final KafkaAdminOperations operations;

    public KafkaAdminService(AdminClient admin, KafkaAdminProperties properties, KafkaAdminOperations operations) {
        this.admin = admin;
        this.properties = properties;
        this.operations = operations;
    }

    // ------------------------------------------------------------------ cluster

    public ClusterInfo describeCluster() {
        DescribeClusterResult result = admin.describeCluster();
        String clusterId = operations.await(result.clusterId());
        Node controller = operations.await(result.controller());
        List<BrokerInfo> brokers = operations.await(result.nodes()).stream()
                .sorted(Comparator.comparingInt(Node::id))
                .map(node -> new BrokerInfo(node.id(), node.host(), node.port(), node.rack(),
                        controller != null && controller.id() == node.id()))
                .toList();
        return new ClusterInfo(clusterId, brokers.size(), brokers);
    }

    // ------------------------------------------------------------------ topicos

    public List<TopicSummary> listTopics(boolean includeInternal) {
        Set<String> names = operations.await(
                admin.listTopics(new ListTopicsOptions().listInternal(includeInternal)).names());
        if (names.isEmpty()) {
            return List.of();
        }
        return operations.await(admin.describeTopics(names).allTopicNames()).values().stream()
                .sorted(Comparator.comparing(TopicDescription::name))
                .map(description -> new TopicSummary(
                        description.name(),
                        description.partitions().size(),
                        replicationFactor(description),
                        description.isInternal()))
                .toList();
    }

    public TopicDetail describeTopic(String name) {
        TopicDescription description = describeTopicDescription(name);
        List<TopicPartition> partitions = description.partitions().stream()
                .map(info -> new TopicPartition(name, info.partition()))
                .toList();
        Map<TopicPartition, Long> earliest = offsets(partitions, OffsetSpec.earliest());
        Map<TopicPartition, Long> latest = offsets(partitions, OffsetSpec.latest());

        List<PartitionInfo> partitionInfos = description.partitions().stream()
                .sorted(Comparator.comparingInt(TopicPartitionInfo::partition))
                .map(info -> {
                    TopicPartition tp = new TopicPartition(name, info.partition());
                    return new PartitionInfo(
                            info.partition(),
                            info.leader() == null ? null : info.leader().id(),
                            info.replicas().stream().map(Node::id).toList(),
                            info.isr().stream().map(Node::id).toList(),
                            earliest.getOrDefault(tp, 0L),
                            latest.getOrDefault(tp, 0L));
                })
                .toList();
        long total = partitionInfos.stream().mapToLong(PartitionInfo::retainedMessages).sum();
        return new TopicDetail(name, description.isInternal(), replicationFactor(description),
                partitionInfos, topicConfigs(name), total);
    }

    public TopicDetail createTopic(TopicCreateRequest request) {
        assertNotProtected(request.name());
        validateConfigKeys(request.safeConfigs().keySet());
        if (request.partitions() > properties.maxPartitions()) {
            throw new InvalidConfigurationException("Maximo " + properties.maxPartitions() + " particiones por topico");
        }
        int brokers = operations.await(admin.describeCluster().nodes()).size();
        if (request.replicationFactor() > brokers) {
            throw new InvalidConfigurationException("El factor de replicacion " + request.replicationFactor()
                    + " supera la cantidad de brokers disponibles (" + brokers + ")");
        }
        NewTopic topic = new NewTopic(request.name(), request.partitions(), request.replicationFactor())
                .configs(request.safeConfigs());
        operations.await(admin.createTopics(List.of(topic)).all());
        log.info("[KAFKA-ADMIN] Topico creado {} particiones={} rf={} configs={}",
                request.name(), request.partitions(), request.replicationFactor(), request.safeConfigs());
        awaitLeaders(request.name(), request.partitions());
        return describeTopic(request.name());
    }

    public TopicDetail updateTopicConfigs(String name, TopicConfigUpdateRequest request) {
        assertNotProtected(name);
        describeTopicDescription(name);
        validateConfigKeys(request.safeSet().keySet());
        validateConfigKeys(request.safeDelete());
        List<AlterConfigOp> ops = new ArrayList<>();
        request.safeSet().forEach((key, value) ->
                ops.add(new AlterConfigOp(new ConfigEntry(key, value), AlterConfigOp.OpType.SET)));
        request.safeDelete().forEach(key ->
                ops.add(new AlterConfigOp(new ConfigEntry(key, null), AlterConfigOp.OpType.DELETE)));
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        operations.await(admin.incrementalAlterConfigs(Map.of(resource, ops)).all());
        log.info("[KAFKA-ADMIN] Configuracion de {} modificada set={} delete={}",
                name, request.safeSet(), request.safeDelete());
        return describeTopic(name);
    }

    public TopicDetail increasePartitions(String name, int totalPartitions) {
        assertNotProtected(name);
        int current = describeTopicDescription(name).partitions().size();
        if (totalPartitions <= current) {
            throw new InvalidConfigurationException("Kafka solo permite aumentar particiones: el topico tiene "
                    + current + " y se solicitaron " + totalPartitions);
        }
        if (totalPartitions > properties.maxPartitions()) {
            throw new InvalidConfigurationException("Maximo " + properties.maxPartitions() + " particiones por topico");
        }
        operations.await(admin.createPartitions(Map.of(name, NewPartitions.increaseTo(totalPartitions))).all());
        log.info("[KAFKA-ADMIN] Particiones de {}: {} -> {}", name, current, totalPartitions);
        awaitLeaders(name, totalPartitions);
        return describeTopic(name);
    }

    public OperationResult deleteTopic(String name) {
        assertNotProtected(name);
        describeTopicDescription(name);
        operations.await(admin.deleteTopics(List.of(name)).all());
        log.info("[KAFKA-ADMIN] Topico eliminado {}", name);
        return new OperationResult("Topico eliminado", name);
    }

    // ------------------------------------------------------------------ consumer groups

    public List<ConsumerGroupSummary> listConsumerGroups() {
        Collection<GroupListing> groups = operations.await(
                admin.listGroups(ListGroupsOptions.forConsumerGroups()).all());
        return groups.stream()
                .sorted(Comparator.comparing(GroupListing::groupId))
                .map(group -> new ConsumerGroupSummary(
                        group.groupId(),
                        group.groupState().map(Enum::name).orElse("UNKNOWN"),
                        group.type().map(Enum::name).orElse(group.protocol())))
                .toList();
    }

    public ConsumerGroupDetail describeConsumerGroup(String groupId) {
        ConsumerGroupDescription description = operations.await(
                admin.describeConsumerGroups(List.of(groupId)).all()).get(groupId);
        Map<TopicPartition, OffsetAndMetadata> committed = committedOffsets(groupId);
        if (description.groupState() == GroupState.DEAD && committed.isEmpty()) {
            throw new ResourceNotFoundException("El consumer group '" + groupId + "' no existe");
        }

        List<MemberInfo> members = description.members().stream()
                .map(member -> new MemberInfo(
                        member.consumerId(),
                        member.clientId(),
                        member.host(),
                        member.assignment().topicPartitions().stream()
                                .sorted(Comparator.comparing(TopicPartition::topic)
                                        .thenComparingInt(TopicPartition::partition))
                                .map(TopicPartition::toString)
                                .toList()))
                .sorted(Comparator.comparing(MemberInfo::clientId))
                .toList();

        Map<TopicPartition, Long> endOffsets = offsets(committed.keySet(), OffsetSpec.latest());
        List<PartitionLag> lags = committed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(TopicPartition::topic)
                        .thenComparingInt(TopicPartition::partition)))
                .map(entry -> {
                    long current = entry.getValue().offset();
                    long end = endOffsets.getOrDefault(entry.getKey(), current);
                    return new PartitionLag(entry.getKey().topic(), entry.getKey().partition(),
                            current, end, Math.max(0, end - current));
                })
                .toList();
        long totalLag = lags.stream().mapToLong(PartitionLag::lag).sum();
        return new ConsumerGroupDetail(
                groupId,
                description.groupState().name(),
                description.partitionAssignor(),
                description.coordinator() == null ? null : description.coordinator().id(),
                members,
                lags,
                totalLag);
    }

    public ConsumerGroupDetail resetOffsets(String groupId, OffsetResetRequest request) {
        ConsumerGroupDescription description = operations.await(
                admin.describeConsumerGroups(List.of(groupId)).all()).get(groupId);
        if (!description.members().isEmpty()) {
            throw new ResourceConflictException("El consumer group '" + groupId + "' tiene "
                    + description.members().size() + " consumidores activos; detengalos antes de resetear offsets");
        }
        List<TopicPartition> partitions = describeTopicDescription(request.topic()).partitions().stream()
                .map(info -> new TopicPartition(request.topic(), info.partition()))
                .filter(tp -> request.partition() == null || tp.partition() == request.partition())
                .toList();
        if (partitions.isEmpty()) {
            throw new ResourceNotFoundException("La particion " + request.partition() + " no existe en " + request.topic());
        }
        Map<TopicPartition, Long> earliest = offsets(partitions, OffsetSpec.earliest());
        Map<TopicPartition, Long> latest = offsets(partitions, OffsetSpec.latest());

        Map<TopicPartition, OffsetAndMetadata> target = new HashMap<>();
        for (TopicPartition tp : partitions) {
            long offset = switch (request.strategy()) {
                case EARLIEST -> earliest.get(tp);
                case LATEST -> latest.get(tp);
                case TO_OFFSET -> {
                    if (request.offset() < earliest.get(tp) || request.offset() > latest.get(tp)) {
                        throw new InvalidConfigurationException("Offset " + request.offset() + " fuera de rango en "
                                + tp + " [" + earliest.get(tp) + ", " + latest.get(tp) + "]");
                    }
                    yield request.offset();
                }
            };
            target.put(tp, new OffsetAndMetadata(offset, "reset via kafka-admin " + request.strategy()));
        }
        operations.await(admin.alterConsumerGroupOffsets(groupId, target).all());
        log.info("[KAFKA-ADMIN] Offsets de {} en {} reseteados a {}", groupId, request.topic(), request.strategy());
        return describeConsumerGroup(groupId);
    }

    public OperationResult deleteConsumerGroup(String groupId) {
        operations.await(admin.deleteConsumerGroups(List.of(groupId)).all());
        log.info("[KAFKA-ADMIN] Consumer group eliminado {}", groupId);
        return new OperationResult("Consumer group eliminado", groupId);
    }

    // ------------------------------------------------------------------ apoyo

    /** Offsets de inicio o fin por particion. */
    public Map<TopicPartition, Long> offsets(Collection<TopicPartition> partitions, OffsetSpec spec) {
        if (partitions.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(tp -> request.put(tp, spec));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> result =
                operations.await(admin.listOffsets(request).all());
        Map<TopicPartition, Long> offsets = new HashMap<>();
        result.forEach((tp, info) -> offsets.put(tp, info.offset()));
        return offsets;
    }

    public Map<TopicPartition, OffsetAndMetadata> committedOffsets(String groupId) {
        Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>(operations.await(
                admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()));
        committed.values().removeIf(java.util.Objects::isNull);
        return committed;
    }

    public TopicDescription describeTopicDescription(String name) {
        return operations.await(admin.describeTopics(List.of(name)).allTopicNames()).get(name);
    }

    private Map<String, String> topicConfigs(String name) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        Config config = operations.await(admin.describeConfigs(List.of(resource)).all()).get(resource);
        Map<String, String> values = new TreeMap<>();
        config.entries().stream()
                .filter(entry -> KEY_CONFIGS.contains(entry.name())
                        || entry.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
                .forEach(entry -> values.put(entry.name(), entry.value()));
        return values;
    }

    /**
     * Tras crear un topico o agregar particiones, el controlador tarda un instante en elegir lideres
     * y propagar la metadata. Se espera (maximo el timeout de operacion) a que todas las particiones
     * tengan lider antes de consultar offsets.
     */
    private void awaitLeaders(String name, int expectedPartitions) {
        long deadline = System.currentTimeMillis() + properties.operationTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            try {
                TopicDescription description = describeTopicDescription(name);
                if (description.partitions().size() >= expectedPartitions
                        && description.partitions().stream().allMatch(info -> info.leader() != null)) {
                    return;
                }
            } catch (ResourceNotFoundException notYetVisible) {
                // La metadata del topico aun no llega a este broker: se reintenta.
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("[KAFKA-ADMIN] {} aun sin lider en todas sus particiones tras {} ms", name, properties.operationTimeoutMs());
    }

    private void assertNotProtected(String name) {
        if (properties.isProtected(name)) {
            throw new ProtectedResourceException("El topico '" + name + "' es interno de Kafka y no se puede modificar");
        }
    }

    private void validateConfigKeys(Collection<String> keys) {
        List<String> invalid = keys.stream()
                .filter(key -> !properties.allowedTopicConfigs().contains(key))
                .sorted()
                .toList();
        if (!invalid.isEmpty()) {
            throw new InvalidConfigurationException("Configuraciones no permitidas: " + invalid
                    + ". Permitidas: " + new java.util.TreeSet<>(properties.allowedTopicConfigs()));
        }
    }

    private static int replicationFactor(TopicDescription description) {
        return description.partitions().isEmpty() ? 0 : description.partitions().get(0).replicas().size();
    }
}
