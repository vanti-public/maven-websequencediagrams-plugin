package com.websequencediagrams;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 */
@Mojo(name = "generate-diagrams", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class WebSequenceDiagramMojo
    extends AbstractMojo
{
    
    /**
     * @parameter default-value="${project.build.outputDirectory}/sequence-diagrams"
     * @required
     */
    // @Parameter(required = true, defaultValue = "${project.build.outputDirectory}/sequence-diagrams")
    @Parameter(required = true, defaultValue = "${project.basedir}/src/main/sequence-diagrams")
    protected File    sourceDirectory;
    
    /**
     * @parameter default-value="${project.build.outputDirectory}/sequence-diagrams"
     * @required
     */
    // @Parameter(required = true, defaultValue = "${project.build.outputDirectory}/sequence-diagrams")
    @Parameter(required = true, defaultValue = "${project.build.directory}/generated-diagrams")
    protected File    outputDirectory;
    
    /**
     * My URL.
     */
    @Parameter(defaultValue = "http://www.websequencediagrams.com")
    private URL       wsdUrl;
    
    @Parameter(defaultValue = "${project.build.directory}/generated-diagrams/.wsdStaleFlag")
    private File      staleFile;
    
    /**
     * @parameter default-value="default"
     */
    @Parameter(required = true, defaultValue = "default")
    protected String  style;
    
    /**
     * @parameter default-value="UTF-8"
     */
    @Parameter(required = true, defaultValue = "UTF-8")
    protected String  encoding;
    
    /**
     * @parameter default-value="1"
     */
    @Parameter(required = true, defaultValue = "1")
    protected Integer apiVersion;
    
    /**
     * @parameter
     */
    @Parameter
    protected String  proxyAddress;
    
    /**
     * @parameter
     */
    @Parameter
    protected Integer proxyPort;
    
    /** @parameter default-value="${project}" */
    // @Parameter(readonly=true, defaultValue="${project}")
    // private MavenProject project;
    
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        getLog().debug("sourceDirectory = " + sourceDirectory.getAbsolutePath());
        getLog().debug("outputDirectory = " + outputDirectory.getAbsolutePath());
        File[] files = sourceDirectory.listFiles( new WsdFile(getLog()) );
        
        if (files.length > 0)
        {
            getLog().debug("Found " + files.length + " source files");
            initProxy();
            generate(files);
        }
        else
        {
            getLog().info("No source files found");
        }
    }
    
    private void generate(File[] aFiles)
    {
        try
        {
            outputDirectory.mkdirs();
            if (isOutputStale(aFiles))
            {
                getLog().info("Generating sequence diagrams ...");
                for (File source : aFiles)
                {
                    getLog().debug("Processing " + source.getName());
                    
                    final String diagramText = readSource(source);
                    final File destination = createOutputFile(source);
                    
                    generateDiagram(diagramText, destination);
                    
                }
                touchStaleFile();
                getLog().info(aFiles.length+" wsf image Generated");
            }
            else
            {
                getLog().info("No changes detected in sequence diagram files - skipping images generation.");
            }
        }
        catch (Exception e)
        {
            getLog().error(e);
        }
        
    }
    
    private String readSource(File source) throws IOException
    {
        FileInputStream stream = new FileInputStream(source);
        try
        {
            final FileChannel fc = stream.getChannel();
            final MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            /* Instead of using default, pass in a decoder. */
            return Charset.defaultCharset().decode(bb).toString();
        }
        finally
        {
            stream.close();
        }
    }
    
    private File createOutputFile(File source)
    {
        String sourceFilename = source.getName();
        String destinationFilename;
        
        if (sourceFilename.indexOf(".") > 0)
        {
            int startOfFileExtension = sourceFilename.lastIndexOf(".");
            destinationFilename = sourceFilename.substring(0, startOfFileExtension) + ".png";
        }
        else
        {
            destinationFilename = sourceFilename + ".png";
        }
        return new File(outputDirectory, destinationFilename);
    }
    
    private void generateDiagram(String diagramText, File destination)
    {
        
        try
        {
            // Build parameter string
            String data = "style=" + style + "&message=" + URLEncoder.encode(diagramText, encoding) + "&apiVersion=" + apiVersion;
            
            // Send the request
            URLConnection conn = connect();
            conn.setDoOutput(true);
            OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
            
            // write parameters
            writer.write(data);
            writer.flush();
            
            // Get the response
            StringBuffer answer = new StringBuffer();
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null)
            {
                answer.append(line);
            }
            writer.close();
            reader.close();
            
            String json = answer.toString();
            int start = json.indexOf("?png=");
            int end = json.indexOf("\"", start);
            
            URL url = new URL(wsdUrl.toString() + "/" + json.substring(start, end));
            
            OutputStream out = new BufferedOutputStream(new FileOutputStream(destination, false));
            InputStream in = url.openConnection().getInputStream();
            byte[] buffer = new byte[1024];
            int numRead;
            while ((numRead = in.read(buffer)) != -1)
            {
                out.write(buffer, 0, numRead);
            }
            
            in.close();
            out.close();
        }
        catch (MalformedURLException ex)
        {
            getLog().error(ex);
        }
        catch (IOException ex)
        {
            getLog().error(ex);
        }
    }
    
    private URLConnection connect() throws IOException
    {
        URL url = new URL(wsdUrl.toString());
        URLConnection conn;
        Proxy proxy = initProxy();
        if (proxy == null)
        {
            conn = url.openConnection();
        }
        else
        {
            conn = url.openConnection(proxy);
        }
        return conn;
    }
    
    private Proxy initProxy()
    {
        Proxy proxy = null;
        if (proxyPort != null && !"".equals(proxyAddress.trim()))
        {
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyAddress, proxyPort));
        }
        return proxy;
    }
    
    private void touchStaleFile() throws IOException
    {
        if (!getStaleFile().exists())
        {
            getStaleFile().getParentFile().mkdirs();
            getStaleFile().createNewFile();
            getLog().debug("Stale flag file created.");
        }
        else
        {
            getStaleFile().setLastModified(System.currentTimeMillis());
        }
    }
    
    /**
     * getStaleFile.
     * 
     * @return
     */
    protected File getStaleFile()
    {
        return staleFile;
    }
    
    private boolean isOutputStale(final File[] aFiles) throws MojoExecutionException
    {
        // We don't use BuildContext for staleness detection, but use the stale flag
        // approach regardless of the runtime environment.
        boolean stale = !getStaleFile().exists();
        
        if (!stale)
        {
            getLog().debug("Stale flag file exists, comparing to wsd file.");
            long staleMod = getStaleFile().lastModified();
            
            for (int i = 0; i < aFiles.length; i++)
            
            {
                if (aFiles[i].lastModified() > staleMod)
                
                {
                    getLog().debug(aFiles[i].getName() + " is newer than the stale flag file.");
                    stale = true;
                    break;
                }
            }
        }
        
        return stale;
    }
    
    final class WsdFile
        implements FileFilter
    {
        private Log log;
        
        public WsdFile(Log log)
        
        {
            this.log = log;
        }
        
        /**
         * 
         * Returns true if the file ends with an xsd extension.
         * 
         * 
         * 
         * @param file
         *            The file being reviewed by the filter.
         * 
         * @return true if an xsd file.
         */
        
        public boolean accept(final java.io.File file)
        
        {
            boolean accept = file.isFile() && file.getName().endsWith(".wsd");
            
            if (log.isDebugEnabled())
            {
                log.debug("accept: " + accept + " for file " + file.getPath());
            }
            
            return accept;
        }
        
    }
}
