"""Failed-login throttling against password guessing (POST /auth and Basic credentials).

Failures are counted in a sliding window under two keys:
- client IP + username: stops guessing one account's password;
- client IP alone: stops spraying many usernames from one address.

Keying on the IP as well as the username means a stranger cannot lock the admin out by sending
wrong passwords from their own address. The state is in memory, so it is per process and is lost
on restart; several workers or replicas each keep their own counts (see README "Security").
"""

import math
import time
from collections import deque
from collections.abc import Callable

from app.core.config import Settings
from app.core.exceptions import TooManyAttemptsError

# Upper bound on tracked keys, so a flood of distinct usernames/IPs cannot grow memory without limit.
MAX_TRACKED_KEYS = 100_000


class LoginAttemptLimiter:
    def __init__(
        self,
        max_per_user: int,
        max_per_ip: int,
        window_seconds: float,
        clock: Callable[[], float] = time.monotonic,
    ):
        self._max_per_user = max_per_user
        self._max_per_ip = max_per_ip
        self._window = window_seconds
        self._clock = clock
        self._failures: dict[str, deque[float]] = {}

    @classmethod
    def from_settings(cls, settings: Settings) -> "LoginAttemptLimiter":
        return cls(
            settings.auth_max_failed_attempts,
            settings.auth_max_failed_attempts_per_ip,
            settings.auth_failure_window_seconds,
        )

    @staticmethod
    def _keys(client_ip: str, username: str) -> tuple[str, str]:
        return f"user:{client_ip}:{username.casefold()}", f"ip:{client_ip}"

    def _recent(self, key: str, now: float) -> deque[float]:
        failures = self._failures.get(key)
        if failures is None:
            return deque()
        while failures and failures[0] <= now - self._window:
            failures.popleft()
        if not failures:
            del self._failures[key]
        return failures

    def check(self, client_ip: str, username: str) -> None:
        """Raise TooManyAttemptsError (429 + Retry-After) while either key is over its limit."""
        now = self._clock()
        retry_after = 0.0
        for key, limit in zip(self._keys(client_ip, username), (self._max_per_user, self._max_per_ip), strict=True):
            if limit <= 0:
                continue
            failures = self._recent(key, now)
            if len(failures) >= limit:
                # Free again once the oldest failure that keeps the count at the limit leaves the window.
                retry_after = max(retry_after, failures[-limit] + self._window - now)
        if retry_after > 0:
            raise TooManyAttemptsError(max(1, math.ceil(retry_after)))

    def record_failure(self, client_ip: str, username: str) -> None:
        now = self._clock()
        keys = self._keys(client_ip, username)
        if len(self._failures) + len(keys) > MAX_TRACKED_KEYS:
            self._evict(now, room=len(keys))
        for key in keys:
            self._recent(key, now)  # prune
            self._failures.setdefault(key, deque()).append(now)

    def record_success(self, client_ip: str, username: str) -> None:
        """A correct password clears that account's count for this IP (not the IP-wide count)."""
        self._failures.pop(self._keys(client_ip, username)[0], None)

    def _evict(self, now: float, room: int) -> None:
        for key in list(self._failures):
            self._recent(key, now)
        # Still full: drop the oldest-inserted keys.
        overflow = len(self._failures) + room - MAX_TRACKED_KEYS
        for key in list(self._failures)[: max(overflow, 0)]:
            del self._failures[key]
