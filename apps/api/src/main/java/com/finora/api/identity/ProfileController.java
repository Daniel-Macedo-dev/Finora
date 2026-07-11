package com.finora.api.identity;

import com.finora.api.identity.AuthDtos.UserResponse;
import com.finora.api.identity.ProfileDtos.ChangePasswordRequest;
import com.finora.api.identity.ProfileDtos.UpdateProfileRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @PutMapping
    public UserResponse update(@Valid @RequestBody UpdateProfileRequest request) {
        return UserResponse.from(service.updateDisplayName(request.displayName()));
    }

    @PostMapping("/password")
    public UserResponse changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                       HttpServletRequest httpRequest) {
        String sessionId = httpRequest.getSession(false) != null
                ? httpRequest.getSession(false).getId()
                : "";
        return UserResponse.from(
                service.changePassword(request.currentPassword(), request.newPassword(), sessionId));
    }
}
