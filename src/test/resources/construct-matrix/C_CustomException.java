// Covers: custom exception class extending Exception, constructor chaining
public class C_CustomException extends Exception {

    private int errorCode;

    public C_CustomException() {
        super();
    }

    public C_CustomException(String message) {
        super(message);
        this.errorCode = -1;
    }

    public C_CustomException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public C_CustomException(String message, Throwable cause) {
        super(message, cause);
    }

    public int getErrorCode() {
        return errorCode;
    }
}
