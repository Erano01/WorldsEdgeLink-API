package me.erano.com.aoe.data;

import jakarta.persistence.*;

import java.util.Objects;
import java.util.List;

@Entity
public class Player {

    @Id
    private Long id; // aoe3 profile id

    private String alias; //osteo, batu, revnak etc

    private String name; // steam/<steamid>

    private Long statGroupId;

    private Long personalStatGroupId; //stat group id

    private String country;

    private String clanName;

    @OneToMany(mappedBy = "player", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LeaderboardEntry> leaderboardEntryList;

    public Player() {
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        Player player = (Player) o;
        return Objects.equals(id, player.id) && Objects.equals(alias, player.alias) && Objects.equals(name, player.name) && Objects.equals(personalStatGroupId, player.personalStatGroupId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(id);
        result = 31 * result + Objects.hashCode(alias);
        result = 31 * result + Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(personalStatGroupId);
        return result;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getPersonalStatGroupId() {
        return personalStatGroupId;
    }

    public void setPersonalStatGroupId(Long personalStatGroupId) {
        this.personalStatGroupId = personalStatGroupId;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public List<LeaderboardEntry> getLeaderboardEntryList() {
        return leaderboardEntryList;
    }

    public void setLeaderboardEntryList(List<LeaderboardEntry> leaderboardEntryList) {
        this.leaderboardEntryList = leaderboardEntryList;
    }

    public String getClanName() {
        return clanName;
    }

    public void setClanName(String clanName) {
        this.clanName = clanName;
    }

    public Long getStatGroupId() {
        return statGroupId;
    }

    public void setStatGroupId(Long statGroupId) {
        this.statGroupId = statGroupId;
    }
}
