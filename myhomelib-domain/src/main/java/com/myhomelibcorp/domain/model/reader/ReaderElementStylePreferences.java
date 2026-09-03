package com.myhomelibcorp.domain.model.reader;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Value;

/**
 * UI/engine-independent persisted override for one semantic Reader element.
 * Blank/null values inherit the global Reader preferences/theme.
 */
@Value
@Builder(toBuilder = true)
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class ReaderElementStylePreferences {
    @Builder.Default String fontFamily = "";
    Double fontSize;
    @Builder.Default double fontScale = 1.0;
    @Builder.Default String fontWeight = "";
    @Builder.Default String color = "";
    @Builder.Default String alignment = "";
    Double spacingBefore;
    Double spacingAfter;
}
