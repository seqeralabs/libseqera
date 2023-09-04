/*
 *  Copyright (c) 2023, Seqera Labs.
 *
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 *  This Source Code Form is "Incompatible With Secondary Licenses", as
 *  defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.wave.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.seqera.wave.config.CondaOpts;
import io.seqera.wave.config.SpackOpts;
import org.apache.commons.lang3.StringUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Helper class to create Dockerfile for Conda and Spack package managers
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class DockerHelper {

    /**
     * Create a Conda environment file starting from one or more Conda package names
     *
     * @param packages
     *      A string listing or more Conda package names separated with a blank character
     *      e.g. {@code samtools=1.0 bedtools=2.0}
     * @param condaChannels
     *      A list of Conda channels
     * @param opts
     *      An instance of {@link CondaOpts} object holding the options for the Conda environment.
     * @return
     *      A path to the Conda environment YAML file. The file is automatically deleted then the JVM exit.
     */
    static public Path condaFileFromPackages(String packages, List<String> condaChannels, CondaOpts opts) {
        final String yaml = condaPackagesToCondaYaml(packages, condaChannels, opts);
        if (yaml == null || yaml.length() == 0)
            return null;
        return toYamlTempFile(yaml);
    }

    static List<String> condaPackagesToList(String packages) {
        if (packages == null || packages.isEmpty())
            return null;
        return Arrays
                .stream(packages.split(" "))
                .filter(it -> !StringUtils.isEmpty(it))
                .map(it -> trim0(it)).collect(Collectors.toList());
    }

    protected static String trim0(String value) {
        if( value==null )
            return null;
        value = value.trim();
        while( value.startsWith("'") && value.endsWith("'") )
            value = value.substring(1,value.length()-1);
        while( value.startsWith("\"") && value.endsWith("\"") )
            value = value.substring(1,value.length()-1);
        return value;
    }

    static String condaPackagesToCondaYaml(String packages, List<String> channels, CondaOpts opts) {
        final List<String> base = condaPackagesToList(opts.basePackages);
        final List<String> custom = condaPackagesToList(packages);
        if (base == null && custom == null)
            return null;

        final List<String> deps = new ArrayList<>();
        if (custom != null)
            deps.addAll(custom);
        if (base != null)
            deps.addAll(base);

        final Map<String, Object> conda = new LinkedHashMap<>();
        if (channels != null && channels.size() > 0) {
            conda.put("channels", channels);
        }
        conda.put("dependencies", deps);

        return dumpCondaYaml(conda);
    }

    static private String dumpCondaYaml(Map<String, Object> conda) {
        DumperOptions dumperOpts = new DumperOptions();
        dumperOpts.setPrettyFlow(false); // Disable pretty formatting
        dumperOpts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); // Use block style
        return new Yaml(new Representer(dumperOpts), dumperOpts).dump(conda);
    }

    /**
     * Get a Conda environment file from a string path.
     *
     * @param condaFile
     *      A file system path where the Conda environment file is located.
     * @param channels
     *      A list of Conda channels. If provided the channels are added to the ones
     *      specified in the Conda environment files.
     * @param opts
     *      An instance of {@link CondaOpts} holding the options for the Conda environment.
     * @return
     *      A {@link Path} to the Conda environment file. It can be the same file as specified
     *      via the condaFile argument or a temporary file if the environment was modified due to
     *      the channels or options specified. 
     */
    public static Path condaFileFromPath(String condaFile, List<String> channels, CondaOpts opts) {
        if( StringUtils.isEmpty(condaFile) )
            throw new IllegalArgumentException("Argument 'condaFile' cannot be empty");
        
        final Path condaEnvPath = Path.of(condaFile);

        // make sure the file exists
        if( !Files.exists(condaEnvPath) ) {
            throw new IllegalArgumentException("The specified Conda environment file cannot be found: " + condaFile);
        }

        // if there's nothing to be marged just return the conda file path
        if( StringUtils.isEmpty(opts.basePackages) && channels==null ) {
            return condaEnvPath;
        }

        // => parse the conda file yaml, add the base packages to it
        final Yaml yaml = new Yaml();
        try {
            // 1. parse the file
            Map<String,Object> root = yaml.load(new FileReader(condaFile));
            // 2. parse the base packages
            final List<String> base = condaPackagesToList(opts.basePackages);
            // 3. append to the specs
            if( base!=null ) {
                List<String> dependencies0 = (List<String>)root.get("dependencies");
                if( dependencies0==null ) {
                    dependencies0 = new ArrayList<>();
                    root.put("dependencies", dependencies0);
                }
                for( String it : base ) {
                    if( !dependencies0.contains(it) )
                        dependencies0.add(it);
                }
            }
            // 4. append channels
            if( channels!=null ) {
                List<String> channels0 = (List<String>)root.get("channels");
                if( channels0==null ) {
                    channels0 = new ArrayList<>();
                    root.put("channels", channels0);
                }
                for( String it : channels ) {
                    if( !channels0.contains(it) )
                        channels0.add(it);
                }
            }
            // 5. return it as a new temp file
            return toYamlTempFile( dumpCondaYaml(root) );
        }
        catch (FileNotFoundException e) {
            throw new IllegalArgumentException("The specified Conda environment file cannot be found: " + condaFile, e);
        }
    }

    static public List<String> spackPackagesToList(String packages) {
        if( packages==null || packages.isEmpty() )
            return null;
        final List<String> entries = Arrays
                .stream(packages.split(" ")).map(it -> trim0(it)).collect(Collectors.toList());
        final List<String> result = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for( String it : entries ) {
            if( it==null || it.isEmpty() || it.isBlank() )
                continue;
            if( !Character.isLetterOrDigit(it.charAt(0)) || it.contains("=") ) {
              current.add(it);
            }
            else {
                if( current.size()>0 )
                    result.add(String.join(" ",current));
                current = new ArrayList<>();
                current.add(it);
            }
        }
        // remaining entries
        if( current.size()>0 )
            result.add(String.join(" ",current));
        return result;
    }

    static public String spackPackagesToSpackYaml(String packages, SpackOpts opts) {
        final List<String> base = spackPackagesToList(opts.basePackages);
        final List<String> custom = spackPackagesToList(packages);
        if( base==null && custom==null )
            return null;

        final List<String> specs = new ArrayList<>();
        if( base!=null )
            specs.addAll(base);
        if( custom!=null )
            specs.addAll(custom);

        final Map<String,Object> concretizer = new LinkedHashMap<>();
        concretizer.put("unify", true);
        concretizer.put("reuse", false);

        final Map<String,Object> spack = new LinkedHashMap<>();
        spack.put("specs", specs);
        spack.put("concretizer", concretizer);

        final Map<String,Object> root = new LinkedHashMap<>();
        root.put("spack", spack);

        return new Yaml().dump(root);
    }

    static public Path spackPackagesToSpackFile(String packages, SpackOpts opts) {
        final String yaml = spackPackagesToSpackYaml(packages, opts);
        if( yaml==null || yaml.length()==0 )
            return null;
        return toYamlTempFile(yaml);
    }

    static private Path toYamlTempFile(String yaml) {
        try {
            final File tempFile = File.createTempFile("nf-temp", ".yaml");
            tempFile.deleteOnExit();
            final Path result = tempFile.toPath();
            Files.write(result, yaml.getBytes());
            return result;
        }
        catch (IOException e) {
            throw new IllegalStateException("Unable to write temporary file - Reason: " + e.getMessage(), e);
        }
    }

    static public String spackFileToDockerFile(SpackOpts opts) {
        // create bindings
        final Map<String,String> binding = spackBinding(opts);
        // final ignored variables
        final List<String> ignore = List.of("spack_runner_image");
        //  return the template
        return renderTemplate0("/templates/spack/dockerfile-spack-file.txt", binding, ignore);
    }

    static public String spackFileToSingularityFile(SpackOpts opts){
        // create bindings
        final Map<String,String> binding = spackBinding(opts);
        // final ignored variables
        final List<String> ignore = List.of("spack_runner_image");
        //  return the template
        return renderTemplate0("/templates/spack/singularityfile-spack-file.txt", binding, ignore);
    }

    static private Map<String,String> spackBinding(SpackOpts opts) {
        final Map<String,String> binding = new HashMap<>();
        binding.put("add_commands", joinCommands(opts.commands));
        return binding;
    }

    static public String condaPackagesToDockerFile(String packages, List<String> condaChannels, CondaOpts opts) {
        return condaPackagesTemplate0(
                "/templates/conda/dockerfile-conda-packages.txt",
                packages,
                condaChannels,
                opts);
    }

    static public String condaPackagesToSingularityFile(String packages, List<String> condaChannels, CondaOpts opts) {
        return condaPackagesTemplate0(
                "/templates/conda/singularityfile-conda-packages.txt",
                packages,
                condaChannels,
                opts);
    }

    static protected String condaPackagesTemplate0(String template, String packages, List<String> condaChannels, CondaOpts opts) {
        final List<String> channels0 = condaChannels!=null ? condaChannels : List.of();
        final String channelsOpts = channels0.stream().map(it -> "-c "+it).collect(Collectors.joining(" "));
        final String image = opts.mambaImage;
        final String target = packages.startsWith("http://") || packages.startsWith("https://")
                ? "-f " + packages
                : packages;
        final Map<String,String> binding = new HashMap<>();
        binding.put("base_image", image);
        binding.put("channel_opts", channelsOpts);
        binding.put("target", target);
        binding.put("base_packages", mambaInstallBasePackage0(opts.basePackages));

        final String result = renderTemplate0(template, binding) ;
        return addCommands(result, opts.commands, template.contains("/singularityfile"));
    }


    static public String condaFileToDockerFile(CondaOpts opts) {
        return condaFileTemplate0("/templates/conda/dockerfile-conda-file.txt", opts);
    }

    static public String condaFileToSingularityFile(CondaOpts opts) {
        return condaFileTemplate0("/templates/conda/singularityfile-conda-file.txt", opts);
    }

    static protected String condaFileTemplate0(String template, CondaOpts opts) {
        // create the binding map
        final Map<String,String> binding = new HashMap<>();
        binding.put("base_image", opts.mambaImage);
        binding.put("base_packages", mambaInstallBasePackage0(opts.basePackages));

        final String result = renderTemplate0(template, binding, List.of("wave_context_dir"));
        return addCommands(result, opts.commands, template.contains("/singularityfile"));
    }

    static private String renderTemplate0(String templatePath, Map<String,String> binding) {
        return renderTemplate0(templatePath, binding, List.of());
    }

    static private String renderTemplate0(String templatePath, Map<String,String> binding, List<String> ignore) {
        final URL template = DockerHelper.class.getResource(templatePath);
        if( template==null )
            throw new IllegalStateException(String.format("Unable to load template '%s' from classpath", templatePath));
        try {
            final InputStream reader = template.openStream();
            return new TemplateRenderer()
                    .withIgnore(ignore)
                    .render(reader, binding);
        }
        catch (IOException e) {
            throw new IllegalStateException(String.format("Unable to read classpath template '%s'", templatePath), e);
        }
    }

    private static String mambaInstallBasePackage0(String basePackages) {
        return !StringUtils.isEmpty(basePackages)
                ? String.format("&& micromamba install -y -n base %s \\", basePackages)
                : null;
    }

    static private String addCommands(String result, List<String> commands, boolean singularity) {
        if( commands==null || commands.isEmpty() )
            return result;
        if( singularity )
            result += "%post\n";
        for( String cmd : commands ) {
            if( singularity ) result += "    ";
            result += cmd + "\n";
        }
        return result;
    }

    static private String joinCommands(List<String> commands) {
        if( commands==null || commands.size()==0 )
            return null;
        StringBuilder result = new StringBuilder();
        for( String cmd : commands ) {
            if( result.length()>0 )
                result.append("\n");
            result.append(cmd);
        }
        return result.toString();
    }

    public static Path addPackagesToSpackFile(String spackFile, SpackOpts opts) {
        // Case A - both empty, nothing to do
        if( StringUtils.isEmpty(spackFile) && StringUtils.isEmpty(opts.basePackages) )
            return null;

        // Case B - the spack file is empty, but some base package are given
        // create a spack file with those packages
        if( StringUtils.isEmpty(spackFile) ) {
            return spackPackagesToSpackFile(null, opts);
        }

        final Path spackEnvPath = Path.of(spackFile);

        // make sure the file exists
        if( !Files.exists(spackEnvPath) ) {
            throw new IllegalArgumentException("The specified Spack environment file cannot be found: " + spackFile);
        }

        // Case C - if not base packages are given just return the spack file as a path
        if( StringUtils.isEmpty(opts.basePackages) ) {
            return spackEnvPath;
        }

        // Case D - last case, both spack file and base packages are specified
        // => parse the spack file yaml, add the base packages to it
        final Yaml yaml = new Yaml();
        try {
            // 1. parse the file
            Map<String,Object> data = yaml.load(new FileReader(spackFile));
            // 2. parse the base packages
            final List<String> base = spackPackagesToList(opts.basePackages);
            // 3. append to the specs
            Map<String,Object> spack = (Map<String,Object>) data.get("spack");
            if( spack==null ) {
                throw new IllegalArgumentException("The specified Spack environment file does not contain a root entry 'spack:' - offending file path: " + spackFile);
            }
            List<String> specs = (List<String>)spack.get("specs");
            if( specs==null ) {
                specs = new ArrayList<>();
                spack.put("specs", specs);
            }
            specs.addAll(base);
            // 5. return it as a new temp file
            return toYamlTempFile( yaml.dump(data) );
        }
        catch (FileNotFoundException e) {
            throw new IllegalArgumentException("The specified Spack environment file cannot be found: " + spackFile, e);
        }
    }

}
