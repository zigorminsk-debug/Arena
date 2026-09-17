package ai.arena.mobile

/*
 * Пять слотов профилей. Каждая Activity объявлена в манифесте со своим
 * android:process (":p1" … ":p5") — именно поэтому сессии аккаунтов
 * полностью изолированы друг от друга.
 */

class ProfileActivity1 : BaseProfileActivity() {
    override val profileId = "p1"
}

class ProfileActivity2 : BaseProfileActivity() {
    override val profileId = "p2"
}

class ProfileActivity3 : BaseProfileActivity() {
    override val profileId = "p3"
}

class ProfileActivity4 : BaseProfileActivity() {
    override val profileId = "p4"
}

class ProfileActivity5 : BaseProfileActivity() {
    override val profileId = "p5"
}
