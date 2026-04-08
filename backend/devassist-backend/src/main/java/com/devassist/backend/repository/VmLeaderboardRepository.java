package com.devassist.backend.repository;

import com.devassist.backend.entity.VmLeaderboard;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VmLeaderboardRepository extends JpaRepository<VmLeaderboard, Long> {
    Optional<VmLeaderboard> findByUsername(String username);
    List<VmLeaderboard> findTop50ByOrderByScoreDescSolvedDesc();
}
