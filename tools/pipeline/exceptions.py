"""Pipeline exceptions."""


class PipelineError(Exception):
    """Base class for pipeline errors."""


class IngestionError(PipelineError):
    """Raised when a source cannot be ingested."""


class ValidationError(PipelineError):
    """Raised when a record or batch fails validation."""


class QualityError(PipelineError):
    """Raised when a data-quality check fails."""