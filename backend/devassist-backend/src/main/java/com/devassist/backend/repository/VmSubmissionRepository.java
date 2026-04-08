package com.devassist.backend.repository;

import com.devassist.backend.entity.VmSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface VmSubmissionRepository extends JpaRepository<VmSubmission, Long> {
    List<VmSubmission> findByUsernameOrderBySubmittedAtDesc(String username);
    List<VmSubmission> findByUsernameAndProblemId(String username, String problemId);

    @Query("SELECT DISTINCT s.problemId FROM VmSubmission s WHERE s.username = :username AND s.verdict = 'Accepted'")
    List<String> findSolvedProblemIds(String username);

    @Query("SELECT DISTINCT s.problemId FROM VmSubmission s WHERE s.username = :username")
    List<String> findAttemptedProblemIds(String username);
}
