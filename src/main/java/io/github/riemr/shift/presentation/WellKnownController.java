package io.github.riemr.shift.presentation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WellKnownController {

    // Return 204 No Content for any ".well-known" probes (e.g. /.well-known/appspecific/...)
    @RequestMapping(path = "/.well-known/**", method = { RequestMethod.GET, RequestMethod.HEAD })
    public ResponseEntity<Void> wellKnown() {
        return ResponseEntity.noContent().build();
    }
}

