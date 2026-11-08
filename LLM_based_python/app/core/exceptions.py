class AppError(Exception):
    """Base class for errors that map directly to an HTTP response."""

    status_code = 500

    def __init__(self, detail: str, headers: dict[str, str] | None = None):
        super().__init__(detail)
        self.detail = detail
        self.headers = headers


class NotFoundError(AppError):
    status_code = 404


class InvalidRequestError(AppError):
    status_code = 422


class InvalidConnectionError(AppError):
    """User-supplied connection details could not be used."""

    status_code = 400


class TargetHostNotAllowedError(AppError):
    """The target host is the system DB or outside the configured target host policy."""

    status_code = 400


class TooManyAttemptsError(AppError):
    """Too many failed logins; the client must wait before trying again."""

    status_code = 429

    def __init__(self, retry_after: int):
        super().__init__("Too many failed login attempts. Try again later.", {"Retry-After": str(retry_after)})


class StaleMetadataError(AppError):
    """Stored metadata no longer matches the target database."""

    status_code = 409


class TargetDatabaseError(AppError):
    """A target database could not be reached or queried."""

    status_code = 502


class LLMError(AppError):
    status_code = 502


class DecryptionError(AppError):
    status_code = 500
