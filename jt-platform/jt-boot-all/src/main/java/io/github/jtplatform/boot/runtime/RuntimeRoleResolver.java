package io.github.jtplatform.boot.runtime;

import java.util.Objects;

public final class RuntimeRoleResolver {
    private static final String PROPERTY = "jt.runtime.role";
    private static final String OPTION = "--" + PROPERTY;
    private static final String ENVIRONMENT = "JT_RUNTIME_ROLE";

    private RuntimeRoleResolver() {
    }

    public static RuntimeRole resolve(String[] args) {
        Objects.requireNonNull(args, "args");
        String commandLine = commandLineValue(args);
        if (commandLine != null) {
            return RuntimeRole.fromProperty(commandLine);
        }
        String systemProperty = System.getProperty(PROPERTY);
        if (systemProperty != null) {
            return RuntimeRole.fromProperty(systemProperty);
        }
        return RuntimeRole.fromProperty(System.getenv(ENVIRONMENT));
    }

    private static String commandLineValue(String[] args) {
        String prefix = OPTION + '=';
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument.startsWith(prefix)) {
                return argument.substring(prefix.length());
            }
            if (OPTION.equals(argument)) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException(OPTION + " requires a value");
                }
                return args[index + 1];
            }
        }
        return null;
    }
}
