package io.github.qingyicode.repopilot.repository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ConnectionController {
    private final ConnectionService connections;
    public ConnectionController(ConnectionService connections) {this.connections=connections;}
    public record Request(@NotBlank @Size(max=2048) String address,boolean authorizeAccess,boolean autoImport) {}
    @PostMapping("/repository-connections") @ResponseStatus(HttpStatus.ACCEPTED)
    public ConnectionJob connect(@Valid @RequestBody Request request) {return connections.connect(request.address(),request.authorizeAccess(),request.autoImport());}
    @GetMapping("/repository-connections/settings") public ConnectionService.AccessSettings settings() {return connections.accessSettings();}
    @GetMapping("/repository-connections/{id}") public ConnectionJob get(@PathVariable String id) {return connections.get(id);}
    @PostMapping("/repository-connections/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public ConnectionJob retry(@PathVariable String id) {return connections.retry(id);}
    @DeleteMapping("/repositories/{id}/authorization") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {connections.revoke(id);}
}
