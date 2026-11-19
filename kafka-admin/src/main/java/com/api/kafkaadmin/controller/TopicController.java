package com.api.kafkaadmin.controller;

import com.api.kafkaadmin.dto.AdminResponses.ClusterInfo;
import com.api.kafkaadmin.dto.AdminResponses.OperationResult;
import com.api.kafkaadmin.dto.AdminResponses.TopicDetail;
import com.api.kafkaadmin.dto.AdminResponses.TopicSummary;
import com.api.kafkaadmin.dto.PartitionIncreaseRequest;
import com.api.kafkaadmin.dto.TopicConfigUpdateRequest;
import com.api.kafkaadmin.dto.TopicCreateRequest;
import com.api.kafkaadmin.service.KafkaAdminService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/*
 * Cluster y topicos: solo reciben, validan (@Valid) y delegan en KafkaAdminService.
 */
@RestController
@RequestMapping("/api/kafka")
public class TopicController {

    private final KafkaAdminService kafkaAdminService;

    public TopicController(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping("/cluster")
    public ClusterInfo cluster() {
        return kafkaAdminService.describeCluster();
    }

    @GetMapping("/topics")
    public List<TopicSummary> listTopics(@RequestParam(defaultValue = "false") boolean includeInternal) {
        return kafkaAdminService.listTopics(includeInternal);
    }

    @GetMapping("/topics/{name}")
    public TopicDetail describeTopic(@PathVariable String name) {
        return kafkaAdminService.describeTopic(name);
    }

    @PostMapping("/topics")
    @ResponseStatus(HttpStatus.CREATED)
    public TopicDetail createTopic(@Valid @RequestBody TopicCreateRequest request) {
        return kafkaAdminService.createTopic(request);
    }

    @PatchMapping("/topics/{name}/configs")
    public TopicDetail updateConfigs(@PathVariable String name, @Valid @RequestBody TopicConfigUpdateRequest request) {
        return kafkaAdminService.updateTopicConfigs(name, request);
    }

    @PostMapping("/topics/{name}/partitions")
    public TopicDetail increasePartitions(@PathVariable String name, @Valid @RequestBody PartitionIncreaseRequest request) {
        return kafkaAdminService.increasePartitions(name, request.totalPartitions());
    }

    @DeleteMapping("/topics/{name}")
    public OperationResult deleteTopic(@PathVariable String name) {
        return kafkaAdminService.deleteTopic(name);
    }
}
