package me.erano.com.aoe.controller;

import me.erano.com.aoe.dto.LeaderboardEntryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    @GetMapping
    public ResponseEntity<LeaderboardEntryResponse> getLeaderboard() {

        return null;
    }

}
