package fr.eternom.eterFriend.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LimitsTest {

    @Test
    void parsesNumbers() {
        assertEquals(100, FriendService.Limits.parse("100", 0));
        assertEquals(25, FriendService.Limits.parse(" 25 ", 0));
    }

    @Test
    void fallsBackOnGarbage() {
        assertEquals(7, FriendService.Limits.parse("vip", 7));
        assertEquals(7, FriendService.Limits.parse("", 7));
    }

    @Test
    void neverNegative() {
        assertEquals(0, FriendService.Limits.parse("-5", 3));
    }
}
