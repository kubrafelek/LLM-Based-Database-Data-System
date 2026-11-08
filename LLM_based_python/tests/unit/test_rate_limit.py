"""Failed-login limiter: sliding window, per-account and per-IP limits, Retry-After."""

import pytest

from app.core import rate_limit
from app.core.exceptions import TooManyAttemptsError
from app.core.rate_limit import LoginAttemptLimiter


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def clock() -> Clock:
    return Clock()


def limiter(clock: Clock, per_user: int = 3, per_ip: int = 10, window: float = 60) -> LoginAttemptLimiter:
    return LoginAttemptLimiter(per_user, per_ip, window, clock=clock)


def fail(lim: LoginAttemptLimiter, times: int, ip: str = "1.2.3.4", user: str = "admin") -> None:
    for _ in range(times):
        lim.record_failure(ip, user)


def test_allows_attempts_below_the_limit(clock):
    lim = limiter(clock)
    fail(lim, 2)
    lim.check("1.2.3.4", "admin")


def test_blocks_at_the_limit_with_retry_after(clock):
    lim = limiter(clock)
    fail(lim, 3)
    clock.now += 15
    with pytest.raises(TooManyAttemptsError) as exc:
        lim.check("1.2.3.4", "admin")
    assert exc.value.status_code == 429
    assert exc.value.headers == {"Retry-After": "45"}


def test_block_expires_with_the_window(clock):
    lim = limiter(clock)
    fail(lim, 3)
    clock.now += 60
    lim.check("1.2.3.4", "admin")


def test_username_is_case_insensitive(clock):
    lim = limiter(clock)
    fail(lim, 3, user="Admin")
    with pytest.raises(TooManyAttemptsError):
        lim.check("1.2.3.4", "ADMIN")


def test_other_ips_are_not_locked_out(clock):
    # An attacker's failures must not lock the real admin out from another address.
    lim = limiter(clock)
    fail(lim, 3, ip="6.6.6.6")
    lim.check("1.2.3.4", "admin")


def test_ip_limit_covers_many_usernames(clock):
    lim = limiter(clock, per_user=100, per_ip=5)
    for i in range(5):
        lim.record_failure("6.6.6.6", f"user{i}")
    with pytest.raises(TooManyAttemptsError):
        lim.check("6.6.6.6", "someone-new")


def test_success_resets_the_account_count_but_not_the_ip_count(clock):
    lim = limiter(clock, per_user=3, per_ip=5)
    fail(lim, 2)
    lim.record_success("1.2.3.4", "admin")
    fail(lim, 2)
    lim.check("1.2.3.4", "admin")  # account count is 2 again, IP count is 4
    lim.record_failure("1.2.3.4", "other")  # IP count 5
    with pytest.raises(TooManyAttemptsError):
        lim.check("1.2.3.4", "other")


def test_zero_disables_a_limit(clock):
    lim = limiter(clock, per_user=0, per_ip=0)
    fail(lim, 50)
    lim.check("1.2.3.4", "admin")


def test_tracked_keys_are_bounded(clock, monkeypatch):
    monkeypatch.setattr(rate_limit, "MAX_TRACKED_KEYS", 10)
    lim = limiter(clock)
    for i in range(50):
        lim.record_failure(f"10.0.0.{i}", "admin")
    assert len(lim._failures) <= 10
