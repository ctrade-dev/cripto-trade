package com.marmitt.application.spring.web;

import com.marmitt.core.exceptions.CodedError;
import com.marmitt.core.exceptions.ConcurrentPositionLockException;
import com.marmitt.core.exceptions.DeadLetterConflictException;
import com.marmitt.core.exceptions.DeadLetterNotFoundException;
import com.marmitt.core.exceptions.ExchangeQueryException;
import com.marmitt.core.exceptions.PortfolioNotFoundException;
import com.marmitt.core.exceptions.RunnerNotFoundException;
import com.marmitt.core.exceptions.RunnerOperationException;
import com.marmitt.core.exceptions.SafeModeTransitionException;
import com.marmitt.core.exceptions.StrategyCatalogException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.time.Instant;

/**
 * Handler global de exceções: centraliza o mapeamento exceção→status e monta o
 * {@link ApiError}. Controllers não decidem status de erro nem montam corpo de erro.
 *
 * <p>Toda recusa traz {@code code} estável (T55): o de negócio vem do core ({@link CodedError}); os
 * genéricos são {@code INVALID_REQUEST}, {@code MALFORMED_BODY}, {@code CAPABILITY_UNSUPPORTED},
 * {@code RESOURCE_NOT_FOUND}, {@code METHOD_NOT_ALLOWED}, {@code UNSUPPORTED_MEDIA_TYPE},
 * {@code HTTP_ERROR} e {@code INTERNAL_ERROR}. O código também fica na requisição para o log de ações do
 * operador.
 *
 * <table>
 *   <tr><td>IllegalArgumentException / validação / parâmetro ausente ou inválido</td><td>400</td></tr>
 *   <tr><td>RunnerNotFoundException / DeadLetterNotFoundException / PortfolioNotFoundException</td><td>404</td></tr>
 *   <tr><td>DeadLetterConflictException / SafeModeTransitionException / RunnerOperationException /
 *           ConcurrentPositionLockException</td><td>409</td></tr>
 *   <tr><td>StrategyCatalogException</td><td>404 / 409 / 400 conforme o código</td></tr>
 *   <tr><td>UnsupportedOperationException (capacidade não suportada pela exchange)</td><td>501</td></tr>
 *   <tr><td>ExchangeQueryException</td><td>502</td></tr>
 *   <tr><td>ErrorResponse (exceções MVC do Spring: 404/405/415...)</td><td>status original</td></tr>
 *   <tr><td>Exception (fallback)</td><td>500</td></tr>
 * </table>
 *
 * <p>Cliente que fechou a conexão no meio da resposta (Broken pipe, connection reset) não é falha do
 * ctrade: não há a quem responder, então nada é escrito e o registro é {@code DEBUG} (T53 F4.12).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, "INVALID_REQUEST");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiError> handleValidation(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, "INVALID_REQUEST");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'", request,
                "INVALID_REQUEST");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                         HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body", request, "MALFORMED_BODY");
    }

    @ExceptionHandler({RunnerNotFoundException.class, DeadLetterNotFoundException.class,
            PortfolioNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, ((CodedError) ex).code());
    }

    @ExceptionHandler({DeadLetterConflictException.class, SafeModeTransitionException.class,
            RunnerOperationException.class, ConcurrentPositionLockException.class})
    public ResponseEntity<ApiError> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, ((CodedError) ex).code());
    }

    @ExceptionHandler(StrategyCatalogException.class)
    public ResponseEntity<ApiError> handleStrategyCatalog(StrategyCatalogException ex, HttpServletRequest request) {
        HttpStatus status = switch (ex.catalogCode().kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
        return build(status, ex.getMessage(), request, ex.code());
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiError> handleUnsupported(UnsupportedOperationException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), request, "CAPABILITY_UNSUPPORTED");
    }

    @ExceptionHandler(ExchangeQueryException.class)
    public ResponseEntity<ApiError> handleExchangeQuery(ExchangeQueryException ex, HttpServletRequest request) {
        log.warn("exchange query failed path={} reason={}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, "Exchange query failed: " + ex.getMessage(), request, ex.code());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            log.debug("client disconnected path={} reason={}", request.getRequestURI(), ex.getMessage());
            return null;
        }
        // Exceções padrão do Spring MVC (ex.: HttpRequestMethodNotSupportedException → 405,
        // NoResourceFoundException → 404, HttpMediaTypeNotSupportedException → 415) implementam
        // ErrorResponse e já carregam o status HTTP correto. Preserva esse status em vez de
        // tratá-las como falha interna 500.
        if (ex instanceof ErrorResponse errorResponse) {
            String detail = errorResponse.getBody().getDetail();
            String message = detail != null ? detail : errorResponse.getBody().getTitle();
            return build(errorResponse.getStatusCode(), message, request, mvcCode(errorResponse.getStatusCode()));
        }
        log.error("unexpected error path={}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request, "INTERNAL_ERROR");
    }

    private static String mvcCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "HTTP_ERROR";
        };
    }

    private static ResponseEntity<ApiError> build(HttpStatusCode status, String message, HttpServletRequest request,
                                                  String code) {
        request.setAttribute(OperatorCommandInterceptor.ERROR_CODE_ATTRIBUTE, code);
        String reason = (status instanceof HttpStatus httpStatus) ? httpStatus.getReasonPhrase() : "";
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                reason,
                message,
                request.getRequestURI(),
                code);
        return ResponseEntity.status(status).body(body);
    }
}
