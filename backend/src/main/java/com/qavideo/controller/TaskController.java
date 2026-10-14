package com.qavideo.controller;

import com.qavideo.common.ApiResponse;
import com.qavideo.common.PageResult;
import com.qavideo.dto.TaskDto;
import com.qavideo.repository.ParseTaskRepository;
import com.qavideo.service.ParseTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final ParseTaskService taskService;
    private final ParseTaskRepository taskRepository;

    public TaskController(ParseTaskService taskService, ParseTaskRepository taskRepository) {
        this.taskService = taskService;
        this.taskRepository = taskRepository;
    }

    /** The front-end polls this while a parse is running. */
    @GetMapping("/{id}")
    public ApiResponse<TaskDto> get(@PathVariable Long id) {
        return taskService.find(id)
                .map(TaskDto::from)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error(40400, "任务不存在: " + id));
    }

    @GetMapping
    public ApiResponse<PageResult<TaskDto>> recent() {
        List<TaskDto> items = taskRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .limit(50)
                .map(TaskDto::from)
                .toList();
        return ApiResponse.ok(PageResult.of(items, items.size(), 0, items.size()));
    }
}
