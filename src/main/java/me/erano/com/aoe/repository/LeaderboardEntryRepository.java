package me.erano.com.aoe.repository;

import me.erano.com.aoe.data.LeaderboardEntry;
import me.erano.com.aoe.data.LeaderboardType;
import me.erano.com.aoe.data.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LeaderboardEntryRepository extends JpaRepository<LeaderboardEntry, Long> {

    Optional<LeaderboardEntry> findByPlayerAndLeaderboardType(Player player, LeaderboardType leaderboardType);

}
