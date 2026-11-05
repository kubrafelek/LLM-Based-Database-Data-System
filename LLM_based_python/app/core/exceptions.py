class AppError(Exception):
    """Base class for errors that map directly to an HTTP response."""

    status_code = 500

    def __init__(self, detail: str):
        super().__init__(detail)
        self.detail = detail


class NotFoundError(AppError):
    status_code = 404


class InvalidRequestError(AppError):
    status_code = 422


class InvalidConnectionError(AppError):
    """User-supplied connection details could not be used."""

    status_code = 400


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
