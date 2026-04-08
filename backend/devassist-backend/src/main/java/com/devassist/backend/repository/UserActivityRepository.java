package com.devassist.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.devassist.backend.entity.UserActivity;
import java.util.List;

@Repository
public interface UserActivityRepository extends JpaRepository<UserActivity, Long> {
    
    // Find the top 15 activities for a specific user, sorted by timestamp descending
    List<UserActivity> findTop15BySupabaseUserIdOrderByTimestampDesc(String userId);

    // Optional: Delete all but the recent ones (if you want to keep the DB light)
    // For now, we only need the fetching logic.
}
