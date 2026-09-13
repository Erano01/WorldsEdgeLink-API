package me.erano.com.aoe.repository;

import me.erano.com.aoe.data.LeaderboardType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaderboardTypeRepository extends JpaRepository<LeaderboardType, Long> {

    Optional<LeaderboardType> findByGameKeyAndRlinkId(String gameKey, Long rlinkId);

    List<LeaderboardType> findAllByGameKey(String gameKey);

    @Query("SELECT lt FROM LeaderboardType lt WHERE lt.gameKey = :gameKey ORDER BY lt.rlinkId")
    List<LeaderboardType> getAllByGameKeySorted(@Param("gameKey") String gameKey);
}

