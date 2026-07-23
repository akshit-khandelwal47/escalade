package dev.escalade.channel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Registers a bean only when a property holds a non-blank value.
 *
 * <p>{@code @ConditionalOnProperty} is not usable here: the credential keys are declared in
 * {@code application.yml} with empty defaults so they can be supplied by environment variable, and
 * that annotation treats a present-but-empty value as a match. A transport would then activate with
 * a blank webhook URL or API key and fail every delivery.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnNonEmptyProperty.Evaluator.class)
public @interface OnNonEmptyProperty {

    String value();

    class Evaluator implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var attributes = metadata.getAnnotationAttributes(OnNonEmptyProperty.class.getName());
            if (attributes == null) {
                return false;
            }
            String key = (String) attributes.get("value");
            return StringUtils.hasText(context.getEnvironment().getProperty(key));
        }
    }
}
