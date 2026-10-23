class Problem(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message
        super().__init__(message)

    def body(self):
        return {"error": {"code": self.code, "message": self.message, "retryable": False}}
