class Problem(Exception):
    def __init__(self, status: int, code: str, message: str, retryable: bool = False):
        self.status, self.code, self.message = status, code, message
        self.retryable = retryable
        super().__init__(message)

    def body(self):
        return {"error": {"code": self.code, "message": self.message, "retryable": self.retryable}}
