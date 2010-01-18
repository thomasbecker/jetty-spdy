// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.JarFile;

import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;

/**
 * Extends the webappclassloader to insert the classloader provided by the osgi
 * bundle at the same level than any other jars palced in the webappclassloader.
 */
public class OSGiWebappClassLoader extends WebAppClassLoader
{

    private Logger __logger = Log.getLogger(OSGiWebappClassLoader.class.getName().toString());

    /**
     * when a logging framework is setup in the osgi classloaders, it can access
     * this and register the classes that must not be found in the jar.
     */
    public static Set<String> JAR_WITH_SUCH_CLASS_MUST_BE_EXCLUDED = new HashSet<String>();

    public static void addClassThatIdentifiesAJarThatMustBeRejected(Class<?> zclass)
    {
        JAR_WITH_SUCH_CLASS_MUST_BE_EXCLUDED.add(zclass.getName().replace('.','/') + ".class");
    }

    public static void addClassThatIdentifiesAJarThatMustBeRejected(String zclassName)
    {
        JAR_WITH_SUCH_CLASS_MUST_BE_EXCLUDED.add(zclassName.replace('.','/') + ".class");
    }

    static
    {
        addClassThatIdentifiesAJarThatMustBeRejected(HttpServlet.class);
        // addClassThatIdentifiesAJarThatMustBeRejected(HttpServlet.class);
    }

    private ClassLoader _osgiBundleClassLoader;
    private boolean _lookInOsgiFirst = true;
    private Set<String> _libsAlreadyInManifest = new HashSet<String>();

    public OSGiWebappClassLoader(ClassLoader parent, WebAppContext context, Bundle contributor) throws IOException
    {
        super(parent,context);
        _osgiBundleClassLoader = WebappRegistrationHelper.BUNDLE_CLASS_LOADER_HELPER.getBundleClassLoader(contributor);
    }

    /**
     * Reads the manifest. If the manifest is already configured to loads a few
     * libs we should not add them to the classpath of the webapp. Not really
     * important as we resolve classes through the osgi classloader first and
     * then default on the libs of the webapp.
     */
    private void computeLibsAlreadyInOSGiClassLoader()
    {
        // TODO
    }

    /**
	 * 
	 */
    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        try
        {
            return _lookInOsgiFirst?_osgiBundleClassLoader.loadClass(name):super.findClass(name);
        }
        catch (ClassNotFoundException cne)
        {
            try
            {
                return _lookInOsgiFirst?super.findClass(name):_osgiBundleClassLoader.loadClass(name);
            }
            catch (ClassNotFoundException cne2)
            {
                throw cne;
            }
        }
    }

    /**
     * Parse the classpath ourselves to be able to filter things. This is a
     * derivative work of the super class
     */
    @Override
    public void addClassPath(String classPath) throws IOException
    {

        StringTokenizer tokenizer = new StringTokenizer(classPath,",;");
        while (tokenizer.hasMoreTokens())
        {
            String path = tokenizer.nextToken();
            Resource resource = getContext().newResource(path);

            // Resolve file path if possible
            File file = resource.getFile();
            if (file != null && isAcceptableLibrary(file,JAR_WITH_SUCH_CLASS_MUST_BE_EXCLUDED))
            {
                super.addClassPath(path);
            }
            else
            {
                __logger.info("Did not add " + path + " to the classloader of the webapp " + getContext().getContextPath());
            }
        }

    }

    /**
     * @param lib
     * @return true if the lib should be included in the webapp classloader.
     */
    private boolean isAcceptableLibrary(File file, Set<String> pathToClassFiles)
    {
        try
        {
            if (file.isDirectory())
            {
                for (String criteria : pathToClassFiles)
                {
                    if (new File(file,criteria).exists())
                    {
                        return false;
                    }
                }
            }
            else
            {
                JarFile jar = null;
                try
                {
                    jar = new JarFile(file);
                    for (String criteria : pathToClassFiles)
                    {
                        if (jar.getEntry(criteria) != null)
                        {
                            return false;
                        }
                    }
                }
                finally
                {
                    if (jar != null)
                        try
                        {
                            jar.close();
                        }
                        catch (IOException ioe)
                        {
                        }
                }
            }
        }
        catch (IOException e)
        {
            // nevermind. just trying our best
            e.printStackTrace();
        }
        return true;
    }

    private static Field _contextField;

    /**
     * In the case of the generation of a webapp via a jetty context file we
     * need a proper classloader to setup the app before we have the
     * WebappContext So we place a fake one there to start with. We replace it
     * with the actual webapp context with this method. We also apply the
     * extraclasspath there at the same time.
     */
    public void setWebappContext(WebAppContext webappContext)
    {
        try
        {
            if (_contextField == null)
            {
                _contextField = WebAppClassLoader.class.getDeclaredField("_context");
                _contextField.setAccessible(true);
            }
            _contextField.set(this,webappContext);
            if (webappContext.getExtraClasspath() != null)
            {
                addClassPath(webappContext.getExtraClasspath());
            }
        }
        catch (Throwable t)
        {
            // humf that will hurt if it does not work.
            t.printStackTrace();
        }
    }
}