package com.qavideo.repository;

import com.qavideo.domain.ParseTask;
import com.qavideo.domain.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ParseTaskRepository extends JpaRepository<ParseTask, Long> {

    List<ParseTask> findByVideoIdOrderByIdDesc(Long videoId);

    Optional<ParseTask> findFirstByVideoIdOrderByIdDesc(Long videoId);

    Optional<ParseTask> findByRequestId(String requestId);

    long countByStatus(TaskStatus status);

    /** Fast "is this video already being parsed?" check used by the dedup lock. */
    @Query("select count(t) from ParseTask t where t.videoId = :videoId and t.status in :statuses")
    long countActiveByVideoId(@Param("videoId") Long videoId,
                              @Param("statuses") Collection<TaskStatus> statuses);

    default long countActiveByVideoId(Long videoId) {
        return countActiveByVideoId(videoId, List.of(TaskStatus.PENDING, TaskStatus.RUNNING));
    }
}
