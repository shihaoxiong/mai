package com.mai.deerflow.backend.runtime.skill;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class SkillNotFoundException extends RuntimeException {

    public SkillNotFoundException(String skillId) {
        super("Skill not found: " + skillId);
    }
}
