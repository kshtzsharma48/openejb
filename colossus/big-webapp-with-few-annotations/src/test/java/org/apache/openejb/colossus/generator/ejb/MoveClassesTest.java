package org.apache.openejb.colossus.generator.ejb;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

import java.io.File;

public class MoveClassesTest {
    @Test
    public void moveGeneratedCompiledClassesToPackageClasses() throws Exception {
        FileUtils.copyDirectory(new File("target/test-classes/" + Main.BASE_PACKAGE), new File("target/classes/" + Main.BASE_PACKAGE));
        FileUtils.deleteDirectory(new File("src/test/java/generated"));
    }
}
