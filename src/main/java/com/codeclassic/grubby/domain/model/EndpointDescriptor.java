package com.codeclassic.grubby.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EndpointDescriptor {
    private String httpMethod; // GET, POST, etc.
    private String path;       // e.g., /api/v1/users
    private String controller; // class name
    private String method;     // method name (if detected)
    private String summary;    // from comments or heuristic
}
