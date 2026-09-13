package me.erano.com.aoe.data;

import jakarta.persistence.*;

import java.util.Objects;

@Entity
@Table(name = "match_type", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"game_key", "rlink_id"})
})
public class MatchType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String gameKey;

    @Column(name = "rlink_id")
    private Long rlinkId;

    private String name;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "leaderboard_type_id")
    private LeaderboardType leaderboardType;

    public MatchType() {
    }

    public MatchType(String gameKey, Long rlinkId, String name) {
        this.gameKey = gameKey;
        this.rlinkId = rlinkId;
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        MatchType matchType = (MatchType) o;
        return Objects.equals(id, matchType.id) && Objects.equals(gameKey, matchType.gameKey) && Objects.equals(rlinkId, matchType.rlinkId) && Objects.equals(name, matchType.name) && Objects.equals(leaderboardType, matchType.leaderboardType);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(id);
        result = 31 * result + Objects.hashCode(gameKey);
        result = 31 * result + Objects.hashCode(rlinkId);
        result = 31 * result + Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(leaderboardType);
        return result;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGameKey() {
        return gameKey;
    }

    public void setGameKey(String gameKey) {
        this.gameKey = gameKey;
    }

    public Long getRlinkId() {
        return rlinkId;
    }

    public void setRlinkId(Long rlinkId) {
        this.rlinkId = rlinkId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LeaderboardType getLeaderboardType() {
        return leaderboardType;
    }

    public void setLeaderboardType(LeaderboardType leaderboardType) {
        this.leaderboardType = leaderboardType;
    }
}

