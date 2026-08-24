package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorDto {
    private String id;
    private String firstName;
    private String middleName;
    private String lastName;
    private String fullName;
    private String shortName;
    private String annotation;
}