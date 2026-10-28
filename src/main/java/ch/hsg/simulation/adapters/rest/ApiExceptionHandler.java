package ch.hsg.simulation.adapters.rest;

import ch.hsg.simulation.adapters.rest.Dtos.ErrorResponse;
import ch.hsg.simulation.model.NotFoundException;
import ch.hsg.simulation.model.SimulationStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorResponse notFound(NotFoundException exception) {
        return new ErrorResponse(exception.getMessage());
    }

    @ExceptionHandler(SimulationStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse conflict(SimulationStateException exception) {
        return new ErrorResponse(exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse badRequest(RuntimeException exception) {
        return new ErrorResponse(exception.getMessage());
    }
}
