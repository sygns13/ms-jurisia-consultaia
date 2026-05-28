package pj.gob.pe.consultaia.utils.beans.responses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T result;
    private double time;

    public static <T> ApiResponse<T> ok(T result, double time) {
        return new ApiResponse<>(true, null, result, time);
    }

    public static <T> ApiResponse<T> error(String message, double time) {
        return new ApiResponse<>(false, message, null, time);
    }
}
