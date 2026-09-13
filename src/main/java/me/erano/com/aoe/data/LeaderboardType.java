package me.erano.com.aoe.data;

import jakarta.persistence.*;

import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "leaderboard_type", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"game_key", "rlink_id"})
})
public class LeaderboardType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String gameKey;

    @Column(name = "rlink_id")
    private Long rlinkId;

    private String name;

    @OneToMany(mappedBy = "leaderboardType", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<MatchType> matchTypes;

    public LeaderboardType(String gameKey, Long rlinkId, String name) {
        this.gameKey = gameKey;
        this.rlinkId = rlinkId;
        this.name = name;
    }

    public LeaderboardType() {
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        LeaderboardType that = (LeaderboardType) o;
        return Objects.equals(id, that.id) && Objects.equals(gameKey, that.gameKey) && Objects.equals(rlinkId, that.rlinkId) && Objects.equals(name, that.name) && Objects.equals(matchTypes, that.matchTypes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(id);
        result = 31 * result + Objects.hashCode(gameKey);
        result = 31 * result + Objects.hashCode(rlinkId);
        result = 31 * result + Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(matchTypes);
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

    public Set<MatchType> getMatchTypes() {
        return matchTypes;
    }

    public void setMatchTypes(Set<MatchType> matchTypes) {
        this.matchTypes = matchTypes;
    }
}

