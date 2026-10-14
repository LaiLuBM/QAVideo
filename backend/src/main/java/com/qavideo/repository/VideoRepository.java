package com.qavideo.repository;

import com.qavideo.domain.Video;
import com.qavideo.domain.enums.VideoStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VideoRepository extends JpaRepository<Video, Long> {

    Optional<Video> findByDedupKey(String dedupKey);

    boolean existsByDedupKey(String dedupKey);

    /**
     * Library query with optional status + keyword filters.
     */
    @Query("""
            select v from Video v
            where (:status is null or v.status = :status)
              and (:keyword is null
                   or lower(v.title) like lower(concat('%', :keyword, '%'))
                   or lower(v.sourceUrl) like lower(concat('%', :keyword, '%'))
                   or lower(v.fileName) like lower(concat('%', :keyword, '%')))
            order by v.createdAt desc
            """)
    Page<Video> search(@Param("status") VideoStatus status,
                       @Param("keyword") String keyword,
                       Pageable pageable);
}
