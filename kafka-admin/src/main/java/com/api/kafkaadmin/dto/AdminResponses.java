package com.api.kafkaadmin.dto;

import java.util.List;
import java.util.Map;

/*
 * Contratos de salida del administrador de Kafka (agrupados por recurso).
 */
public final class AdminResponses {

    private AdminResponses() {
    }

    public record BrokerInfo(int id, String host, int port, String rack, boolean controller) {
    }

    public record ClusterInfo(String clusterId, int brokerCount, List<BrokerInfo> brokers) {
    }

    public record TopicSummary(String name, int partitions, int replicationFactor, boolean internal) {
    }

    /** Detalle de particion: lider, replicas, ISR y offsets (mensajes retenidos = end - beginning). */
    public record PartitionInfo(
            int partition,
            Integer leader,
            List<Integer> replicas,
            List<Integer> inSyncReplicas,
            long beginningOffset,
            long endOffset) {

        public long retainedMessages() {
            return Math.max(0, endOffset - beginningOffset);
        }

        public boolean underReplicated() {
            return inSyncReplicas.size() < replicas.size();
        }
    }

    public record TopicDetail(
            String name,
            boolean internal,
            int replicationFactor,
            List<PartitionInfo> partitions,
            Map<String, String> configs,
            long totalMessages) {
    }

    public record ConsumerGroupSummary(String groupId, String state, String type) {
    }

    public record MemberInfo(String memberId, String clientId, String host, List<String> assignedPartitions) {
    }

    /** Lag = LogEndOffset - CurrentOffset (guia 3.3.1). */
    public record PartitionLag(String topic, int partition, long committedOffset, long endOffset, long lag) {
    }

    public record ConsumerGroupDetail(
            String groupId,
            String state,
            String partitionAssignor,
            Integer coordinator,
            List<MemberInfo> members,
            List<PartitionLag> offsets,
            long totalLag) {
    }

    public record DeadLetterTopic(String topic, String sourceTopic, long messages) {
    }

    public record DeadLetterSummary(boolean alert, long totalMessages, List<DeadLetterTopic> topics) {
    }

    /** Mensaje leido de una DLT, con los headers que agrega DeadLetterPublishingRecoverer. */
    public record DeadLetterMessage(
            int partition,
            long offset,
            String key,
            String value,
            long timestamp,
            Map<String, String> headers) {
    }

    public record ReprocessResult(String deadLetterTopic, String targetTopic, int republished, String groupId) {
    }

    public record OperationResult(String message, String resource) {
    }
}
