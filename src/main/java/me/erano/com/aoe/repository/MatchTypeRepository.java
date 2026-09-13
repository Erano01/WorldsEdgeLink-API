package me.erano.com.aoe.repository;

import me.erano.com.aoe.data.MatchType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchTypeRepository extends JpaRepository<MatchType, Long> {

    Optional<MatchType> findByGameKeyAndRlinkId(String gameKey, Long rlinkId);

    List<MatchType> findAllByGameKey(String gameKey);

    @Query("SELECT mt FROM MatchType mt WHERE mt.gameKey = :gameKey ORDER BY mt.rlinkId")
    List<MatchType> getAllByGameKeySorted(@Param("gameKey") String gameKey);
}

