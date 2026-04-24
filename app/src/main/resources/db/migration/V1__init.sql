-- V1__init.sql
-- Foundation: 9 tables (Sprint 0)
-- ADR-0001 (Flyway), ADR-0002 (foundation)
-- Server Notion spec 기반. 모든 FK는 BIGINT, PK는 BIGSERIAL.

-- ========== users ==========
CREATE TABLE users (
    id                BIGSERIAL    PRIMARY KEY,
    kakao_id          BIGINT       NOT NULL UNIQUE,
    nickname          VARCHAR(50)  NOT NULL,
    profile_image_url TEXT,
    phone_number      VARCHAR(64)  UNIQUE,                           -- SHA-256 hash, nullable (ADR-0008 A 거부 시)
    phone_verified    BOOLEAN      NOT NULL DEFAULT FALSE,           -- true = Kakao scope로 받은 번호
    fcm_token         TEXT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',        -- ACTIVE / SUSPENDED / DELETED
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ========== friendships ==========
CREATE TABLE friendships (
    id            BIGSERIAL    PRIMARY KEY,
    requester_id  BIGINT       NOT NULL REFERENCES users(id),
    receiver_id   BIGINT       NOT NULL REFERENCES users(id),
    status        VARCHAR(20)  NOT NULL,                             -- PENDING / ACCEPTED / REJECTED / BLOCKED
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at   TIMESTAMP,
    CONSTRAINT uq_friendships_requester_receiver UNIQUE (requester_id, receiver_id)
);
CREATE INDEX idx_friendships_receiver_status  ON friendships(receiver_id, status);
CREATE INDEX idx_friendships_requester_status ON friendships(requester_id, status);

-- ========== challenges ==========
CREATE TABLE challenges (
    id                 BIGSERIAL    PRIMARY KEY,
    challenger_id      BIGINT       NOT NULL REFERENCES users(id),
    opponent_id        BIGINT       NOT NULL REFERENCES users(id),
    challenger_mission TEXT         NOT NULL,
    opponent_mission   TEXT         NOT NULL,
    bet_content        TEXT         NOT NULL,
    challenge_date     DATE         NOT NULL,
    deadline           TIMESTAMP    NOT NULL,
    status             VARCHAR(20)  NOT NULL,                        -- PENDING / ACCEPTED / REJECTED / CONTRACT_SIGNING / IN_PROGRESS / COMPLETED / EXPIRED
    result             VARCHAR(20),                                  -- CHALLENGER_WIN / OPPONENT_WIN / DRAW / BOTH_LOSE
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at       TIMESTAMP
);
CREATE INDEX idx_challenges_challenger_status ON challenges(challenger_id, status);
CREATE INDEX idx_challenges_opponent_status   ON challenges(opponent_id, status);
CREATE INDEX idx_challenges_deadline_status   ON challenges(deadline, status);

-- ========== contracts ==========
CREATE TABLE contracts (
    id                       BIGSERIAL  PRIMARY KEY,
    challenge_id             BIGINT     NOT NULL UNIQUE REFERENCES challenges(id),
    content                  TEXT       NOT NULL,
    challenger_signature_url TEXT,
    opponent_signature_url   TEXT,
    challenger_signed_at     TIMESTAMP,
    opponent_signed_at       TIMESTAMP,
    is_finalized             BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ========== verifications ==========
CREATE TABLE verifications (
    id           BIGSERIAL  PRIMARY KEY,
    challenge_id BIGINT     NOT NULL REFERENCES challenges(id),
    user_id      BIGINT     NOT NULL REFERENCES users(id),
    photo_url    TEXT       NOT NULL,
    verified_at  TIMESTAMP  NOT NULL,
    CONSTRAINT uq_verifications_challenge_user UNIQUE (challenge_id, user_id)
);

-- ========== taunt_messages ==========
CREATE TABLE taunt_messages (
    id           BIGSERIAL  PRIMARY KEY,
    challenge_id BIGINT     NOT NULL REFERENCES challenges(id),
    sender_id    BIGINT     NOT NULL REFERENCES users(id),
    message      TEXT       NOT NULL,
    is_preset    BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_taunt_messages_challenge_created ON taunt_messages(challenge_id, created_at DESC);

-- ========== notifications ==========
CREATE TABLE notifications (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users(id),
    type         VARCHAR(30)  NOT NULL,                              -- CHALLENGE_REQUEST / SIGN_REQUEST / REMIND / OPPONENT_VERIFIED / RESULT / TAUNT / FRIEND_REQUEST
    title        VARCHAR(100) NOT NULL,
    body         TEXT         NOT NULL,
    reference_id BIGINT,
    is_read      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notifications_user_read_created ON notifications(user_id, is_read, created_at DESC);

-- ========== user_stats ==========
CREATE TABLE user_stats (
    user_id          BIGINT     PRIMARY KEY REFERENCES users(id),
    total_challenges INT        NOT NULL DEFAULT 0,
    wins             INT        NOT NULL DEFAULT 0,
    losses           INT        NOT NULL DEFAULT 0,
    draws            INT        NOT NULL DEFAULT 0,
    current_streak   INT        NOT NULL DEFAULT 0,
    max_streak       INT        NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ========== friend_records ==========
CREATE TABLE friend_records (
    id        BIGSERIAL  PRIMARY KEY,
    user_id   BIGINT     NOT NULL REFERENCES users(id),
    friend_id BIGINT     NOT NULL REFERENCES users(id),
    wins      INT        NOT NULL DEFAULT 0,
    losses    INT        NOT NULL DEFAULT 0,
    draws     INT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_friend_records_user_friend UNIQUE (user_id, friend_id)
);
