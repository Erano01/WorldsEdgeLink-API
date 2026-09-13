package me.erano.com.aoe.data;

import jakarta.persistence.*;

import java.util.Objects;

@Entity
@Table(name = "leaderboard_entry", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"player_id", "game_key", "leaderboard_type_id"})
})
public class LeaderboardEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String gameKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leaderboard_type_id", nullable = false)
    private LeaderboardType leaderboardType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    private int rating;

    private int gameCount;

    private int win;

    private int loss;

    private int winStreak;

    private int winRate;

    private int maxElo;

    private int maxStreak;

    private int disputes;

    private int drops;

    public LeaderboardEntry() {
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        LeaderboardEntry that = (LeaderboardEntry) o;
        return Objects.equals(id, that.id) && Objects.equals(leaderboardType, that.leaderboardType) && Objects.equals(player, that.player);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(id);
        result = 31 * result + Objects.hashCode(leaderboardType);
        result = 31 * result + Objects.hashCode(player);
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

    public LeaderboardType getLeaderboardType() {
        return leaderboardType;
    }

    public void setLeaderboardType(LeaderboardType leaderboardType) {
        this.leaderboardType = leaderboardType;
    }

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = rating;
    }

    public int getGameCount() {
        return gameCount;
    }

    public void setGameCount(int gameCount) {
        this.gameCount = gameCount;
    }

    public int getWin() {
        return win;
    }

    public void setWin(int win) {
        this.win = win;
    }

    public int getLoss() {
        return loss;
    }

    public void setLoss(int loss) {
        this.loss = loss;
    }

    public int getWinStreak() {
        return winStreak;
    }

    public void setWinStreak(int winStreak) {
        this.winStreak = winStreak;
    }

    public int getWinRate() {
        return winRate;
    }

    public void setWinRate(int winRate) {
        this.winRate = winRate;
    }

    public int getMaxElo() {
        return maxElo;
    }

    public void setMaxElo(int maxElo) {
        this.maxElo = maxElo;
    }

    public int getMaxStreak() {
        return maxStreak;
    }

    public void setMaxStreak(int maxStreak) {
        this.maxStreak = maxStreak;
    }

    public int getDisputes() {
        return disputes;
    }

    public void setDisputes(int disputes) {
        this.disputes = disputes;
    }

    public int getDrops() {
        return drops;
    }

    public void setDrops(int drops) {
        this.drops = drops;
    }
}

