/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.server.errors;

import io.airbyte.api.model.generated.InvalidInputExceptionInfo;
import io.airbyte.api.model.generated.InvalidInputProperty;
import io.airbyte.commons.json.Jsons;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.core.util.Throwables;

/**
 * Exception mapper for when an input value is not valid.
 */
@Provider
public class InvalidInputExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

  /**
   * Static factory for invalid input.
   *
   * @param cve exception with invalidity info
   * @return exception
   */
  public static InvalidInputExceptionInfo infoFromConstraints(final ConstraintViolationException cve) {
    final InvalidInputExceptionInfo exceptionInfo = new InvalidInputExceptionInfo()
        .exceptionClassName(cve.getClass().getName())
        .message("Some properties contained invalid input.")
        .exceptionStack(Throwables.toStringList(cve));

    final List<InvalidInputProperty> props = new ArrayList<InvalidInputProperty>();
    for (final ConstraintViolation<?> cv : cve.getConstraintViolations()) {
      props.add(new InvalidInputProperty()
          .propertyPath(cv.getPropertyPath().toString())
          .message(cv.getMessage())
          .invalidValue(cv.getInvalidValue() != null ? cv.getInvalidValue().toString() : "null"));
    }
    exceptionInfo.validationErrors(props);
    return exceptionInfo;
  }

  @Override
  public Response toResponse(final ConstraintViolationException e) {
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(Jsons.serialize(InvalidInputExceptionMapper.infoFromConstraints(e)))
        .type("application/json")
        .build();
  }

}