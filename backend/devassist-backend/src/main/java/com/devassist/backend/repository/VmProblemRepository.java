package com.devassist.backend.repository;

import com.devassist.backend.entity.VmProblem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VmProblemRepository extends JpaRepository<VmProblem, String> {
    List<VmProblem> findByDifficultyOrderByIdAsc(String difficulty);
}
