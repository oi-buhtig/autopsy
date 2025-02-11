 /*
 *
 * Autopsy Forensic Browser
 * 
 * Copyright 2012-2014 Basis Technology Corp.
 * 
 * Copyright 2012 42six Solutions.
 * Contact: aebadirad <at> 42six <dot> com
 * Project Contact/Architect: carrier <at> sleuthkit <dot> org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.recentactivity;

import java.io.*;
import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.openide.modules.InstalledFileLocator;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.coreutils.ExecUtil;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.PlatformUtil;
import org.sleuthkit.autopsy.datamodel.ContentUtils;
import org.sleuthkit.autopsy.ingest.DataSourceIngestModuleProcessTerminator;
import org.sleuthkit.autopsy.ingest.IngestJobContext;
import org.sleuthkit.autopsy.recentactivity.UsbDeviceIdMapper.USBInfo;
import org.sleuthkit.datamodel.*;
import org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE;
import org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE;
import org.sleuthkit.datamodel.OSUtility; // TEMP
import org.sleuthkit.autopsy.casemodule.Case;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Extract windows registry data using regripper. Runs two versions of
 * regripper. One is the generally available set of plug-ins and the second is a
 * set that were customized for Autopsy to produce a more structured output of
 * XML so that we can parse and turn into blackboard artifacts.
 */
class ExtractRegistry extends Extract {

    private Logger logger = Logger.getLogger(this.getClass().getName());
    private String RR_PATH;
    private String RR_FULL_PATH;
    private String rrHome;  // The current version of RegRipper need to be run from its own directory
    private String rrFullHome;
    private boolean rrFound = false;    // true if we found the Autopsy-specific version of regripper
    private boolean rrFullFound = false; // true if we found the full version of regripper    
    private Content dataSource;
    private IngestJobContext context;
    final private static UsbDeviceIdMapper usbMapper = new UsbDeviceIdMapper();

    ExtractRegistry() {
        moduleName = NbBundle.getMessage(ExtractIE.class, "ExtractRegistry.moduleName.text");
        final File rrRoot = InstalledFileLocator.getDefault().locate("rr", ExtractRegistry.class.getPackage().getName(), false); //NON-NLS
        if (rrRoot == null) {
            logger.log(Level.SEVERE, "RegRipper not found"); //NON-NLS
            rrFound = false;
            return;
        } else {
            rrFound = true;
        }

        rrHome = rrRoot.getAbsolutePath();
        logger.log(Level.INFO, "RegRipper home: {0}", rrHome); //NON-NLS

        if (PlatformUtil.isWindowsOS()) {
            RR_PATH = rrHome + File.separator + "rip.exe"; //NON-NLS
        } else {
            RR_PATH = "perl " + rrHome + File.separator + "rip.pl"; //NON-NLS
        }

        final File rrFullRoot = InstalledFileLocator.getDefault().locate("rr-full", ExtractRegistry.class.getPackage().getName(), false); //NON-NLS
        if (rrFullRoot == null) {
            logger.log(Level.SEVERE, "RegRipper Full not found"); //NON-NLS
            rrFullFound = false;
        } else {
            rrFullFound = true;
        }

        if (rrFullRoot != null) {
            rrFullHome = rrFullRoot.getAbsolutePath();
        } else {
            rrFullHome = "";
        }
        logger.log(Level.INFO, "RegRipper Full home: {0}", rrFullHome); //NON-NLS

        if (PlatformUtil.isWindowsOS()) {
            RR_FULL_PATH = rrFullHome + File.separator + "rip.exe"; //NON-NLS
        } else {
            RR_FULL_PATH = "perl " + rrFullHome + File.separator + "rip.pl"; //NON-NLS
        }
    }

    /**
     * Search for the registry hives on the system.
     */
    private List<AbstractFile> findRegistryFiles() {
        List<AbstractFile> allRegistryFiles = new ArrayList<>();
        org.sleuthkit.autopsy.casemodule.services.FileManager fileManager = currentCase.getServices().getFileManager();

        // find the user-specific ntuser-dat files
        try {
            allRegistryFiles.addAll(fileManager.findFiles(dataSource, "ntuser.dat")); //NON-NLS
        } catch (TskCoreException ex) {
            logger.log(Level.WARNING, "Error fetching 'ntuser.dat' file."); //NON-NLS
        }

        // find the system hives'
        String[] regFileNames = new String[]{"system", "software", "security", "sam"}; //NON-NLS
        for (String regFileName : regFileNames) {
            try {
                allRegistryFiles.addAll(fileManager.findFiles(dataSource, regFileName, "/system32/config")); //NON-NLS
            } catch (TskCoreException ex) {
                String msg = NbBundle.getMessage(this.getClass(),
                        "ExtractRegistry.findRegFiles.errMsg.errReadingFile", regFileName);
                logger.log(Level.WARNING, msg);
                this.addErrorMessage(this.getName() + ": " + msg);
            }
        }
        return allRegistryFiles;
    }

    /**
     * Identifies registry files in the database by mtimeItem, runs regripper on
     * them, and parses the output.
     */
    private void analyzeRegistryFiles() {
        List<AbstractFile> allRegistryFiles = findRegistryFiles();

        // open the log file
        FileWriter logFile = null;
        try {
            logFile = new FileWriter(RAImageIngestModule.getRAOutputPath(currentCase, "reg") + File.separator + "regripper-info.txt"); //NON-NLS
        } catch (IOException ex) {
            logger.log(Level.SEVERE, null, ex);
        }

        int j = 0;
        for (AbstractFile regFile : allRegistryFiles) {
            String regFileName = regFile.getName();
            String regFileNameLocal = RAImageIngestModule.getRATempPath(currentCase, "reg") + File.separator + regFileName;
            String outputPathBase = RAImageIngestModule.getRAOutputPath(currentCase, "reg") + File.separator + regFileName + "-regripper-" + Integer.toString(j++); //NON-NLS
            File regFileNameLocalFile = new File(regFileNameLocal);
            try {
                ContentUtils.writeToFile(regFile, regFileNameLocalFile);
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Error writing the temp registry file. {0}", ex); //NON-NLS
                this.addErrorMessage(
                        NbBundle.getMessage(this.getClass(), "ExtractRegistry.analyzeRegFiles.errMsg.errWritingTemp",
                                this.getName(), regFileName));
                continue;
            }

            if (context.dataSourceIngestIsCancelled()) {
                break;
            }

            try {
                if (logFile != null) {
                    logFile.write(Integer.toString(j - 1) + "\t" + regFile.getUniquePath() + "\n");
                }
            } catch (TskCoreException | IOException ex) {
                logger.log(Level.SEVERE, null, ex);
            }

            logger.log(Level.INFO, "{0}- Now getting registry information from {1}", new Object[]{moduleName, regFileNameLocal}); //NON-NLS
            RegOutputFiles regOutputFiles = ripRegistryFile(regFileNameLocal, outputPathBase);
            if (context.dataSourceIngestIsCancelled()) {
                break;
            }

            // parse the autopsy-specific output
            if (regOutputFiles.autopsyPlugins.isEmpty() == false) {
                if (parseAutopsyPluginOutput(regOutputFiles.autopsyPlugins, regFile) == false) {
                    this.addErrorMessage(
                            NbBundle.getMessage(this.getClass(), "ExtractRegistry.analyzeRegFiles.failedParsingResults",
                                    this.getName(), regFileName));
                }
            }

            // create a report for the full output
            if (regOutputFiles.fullPlugins.isEmpty() == false) {
                try {
                    currentCase.addReport(regOutputFiles.fullPlugins, NbBundle.getMessage(this.getClass(), "ExtractRegistry.parentModuleName.noSpace"), "RegRipper " + regFile.getUniquePath()); //NON-NLS
                } catch (TskCoreException e) {
                    this.addErrorMessage("Error adding regripper output as Autopsy report: " + e.getLocalizedMessage()); //NON-NLS
                }
            }

            // delete the hive
            regFileNameLocalFile.delete();
        }

        try {
            if (logFile != null) {
                logFile.close();
            }
        } catch (IOException ex) {
            logger.log(Level.SEVERE, null, ex);
        }
    }

    private class RegOutputFiles {

        public String autopsyPlugins = "";
        public String fullPlugins = "";
    }

    /**
     * Execute regripper on the given registry.
     *
     * @param regFilePath     Path to local copy of registry
     * @param outFilePathBase Path to location to save output file to. Base
     *                        mtimeItem that will be extended on
     */
    private RegOutputFiles ripRegistryFile(String regFilePath, String outFilePathBase) {
        String autopsyType = "";    // Type argument for rr for autopsy-specific modules
        String fullType;   // Type argument for rr for full set of modules

        RegOutputFiles regOutputFiles = new RegOutputFiles();

        if (regFilePath.toLowerCase().contains("system")) { //NON-NLS
            autopsyType = "autopsysystem"; //NON-NLS
            fullType = "system"; //NON-NLS
        } else if (regFilePath.toLowerCase().contains("software")) { //NON-NLS
            autopsyType = "autopsysoftware"; //NON-NLS
            fullType = "software"; //NON-NLS
        } else if (regFilePath.toLowerCase().contains("ntuser")) { //NON-NLS
            autopsyType = "autopsyntuser"; //NON-NLS
            fullType = "ntuser"; //NON-NLS
        } else if (regFilePath.toLowerCase().contains("sam")) { //NON-NLS
            fullType = "sam"; //NON-NLS
        } else if (regFilePath.toLowerCase().contains("security")) { //NON-NLS
            fullType = "security"; //NON-NLS
        } else {
            return regOutputFiles;
        }

        // run the autopsy-specific set of modules
        if (!autopsyType.isEmpty() && rrFound) {
            regOutputFiles.autopsyPlugins = outFilePathBase + "-autopsy.txt"; //NON-NLS
            String errFilePath = outFilePathBase + "-autopsy.err.txt"; //NON-NLS
            logger.log(Level.INFO, "Writing RegRipper results to: {0}", regOutputFiles.autopsyPlugins); //NON-NLS
            executeRegRipper(RR_PATH, rrHome, regFilePath, autopsyType, regOutputFiles.autopsyPlugins, errFilePath);
        }
        if (context.dataSourceIngestIsCancelled()) {
            return regOutputFiles;
        }

        // run the full set of rr modules
        if (!fullType.isEmpty() && rrFullFound) {
            regOutputFiles.fullPlugins = outFilePathBase + "-full.txt"; //NON-NLS
            String errFilePath = outFilePathBase + "-full.err.txt"; //NON-NLS
            logger.log(Level.INFO, "Writing Full RegRipper results to: {0}", regOutputFiles.fullPlugins); //NON-NLS
            executeRegRipper(RR_FULL_PATH, rrFullHome, regFilePath, fullType, regOutputFiles.fullPlugins, errFilePath);
        }
        return regOutputFiles;
    }

    private void executeRegRipper(String regRipperPath, String regRipperHomeDir, String hiveFilePath, String hiveFileType, String outputFile, String errFile) {
        try {
            logger.log(Level.INFO, "Writing RegRipper results to: {0}", outputFile); //NON-NLS
            List<String> commandLine = new ArrayList<>();
            commandLine.add(regRipperPath);
            commandLine.add("-r"); //NON-NLS
            commandLine.add(hiveFilePath);
            commandLine.add("-f"); //NON-NLS
            commandLine.add(hiveFileType);

            ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
            processBuilder.directory(new File(regRipperHomeDir)); // RegRipper 2.8 has to be run from its own directory
            processBuilder.redirectOutput(new File(outputFile));
            processBuilder.redirectError(new File(errFile));
            ExecUtil.execute(processBuilder, new DataSourceIngestModuleProcessTerminator(context));
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Unable to run RegRipper", ex); //NON-NLS
            this.addErrorMessage(NbBundle.getMessage(this.getClass(), "ExtractRegistry.execRegRip.errMsg.failedAnalyzeRegFile", this.getName()));
        }
    }

    // @@@ VERIFY that we are doing the right thing when we parse multiple NTUSER.DAT
    /**
     *
     * @param regFilePath Path to the output file produced by RegRipper.
     * @param regFile     File object for registry that we are parsing (to make
     *                    blackboard artifacts with)
     *
     * @return
     */
    private boolean parseAutopsyPluginOutput(String regFilePath, AbstractFile regFile) {
        FileInputStream fstream = null;
        try {
            SleuthkitCase tempDb = currentCase.getSleuthkitCase();

            // Read the file in and create a Document and elements
            File regfile = new File(regFilePath);
            fstream = new FileInputStream(regfile);

            String regString = new Scanner(fstream, "UTF-8").useDelimiter("\\Z").next(); //NON-NLS
            String startdoc = "<?xml version=\"1.0\"?><document>"; //NON-NLS
            String result = regString.replaceAll("----------------------------------------", "");
            result = result.replaceAll("\\n", ""); //NON-NLS
            result = result.replaceAll("\\r", ""); //NON-NLS
            result = result.replaceAll("'", "&apos;"); //NON-NLS
            result = result.replaceAll("&", "&amp;"); //NON-NLS
            result = result.replace('\0', ' '); // NON-NLS
            String enddoc = "</document>"; //NON-NLS
            String stringdoc = startdoc + result + enddoc;
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(stringdoc)));

            // cycle through the elements in the doc
            Element oroot = doc.getDocumentElement();
            NodeList children = oroot.getChildNodes();
            int len = children.getLength();
            for (int i = 0; i < len; i++) {
                Element tempnode = (Element) children.item(i);

                String dataType = tempnode.getNodeName();

                NodeList timenodes = tempnode.getElementsByTagName("mtime"); //NON-NLS
                Long mtime = null;
                if (timenodes.getLength() > 0) {
                    Element timenode = (Element) timenodes.item(0);
                    String etime = timenode.getTextContent();
                    try {
                        Long epochtime = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy").parse(etime).getTime();
                        mtime = epochtime;
                        String Tempdate = mtime.toString();
                        mtime = Long.valueOf(Tempdate) / 1000;
                    } catch (ParseException ex) {
                        logger.log(Level.WARNING, "Failed to parse epoch time when parsing the registry."); //NON-NLS
                    }
                }

                NodeList artroots = tempnode.getElementsByTagName("artifacts"); //NON-NLS
                if (artroots.getLength() == 0) {
                    // If there isn't an artifact node, skip this entry
                    continue;
                }

                Element artroot = (Element) artroots.item(0);
                NodeList myartlist = artroot.getChildNodes();
                String parentModuleName = NbBundle.getMessage(this.getClass(), "ExtractRegistry.parentModuleName.noSpace");
                String winver = "";

                // If all artifact nodes should really go under one Blackboard artifact, need to process it differently
                if (dataType.equals("WinVersion")) { //NON-NLS

                    String version = "";
                    String systemRoot = "";
                    String productId = "";
                    String regOwner = "";
                    String regOrg = "";
                    Long installtime = null;

                    for (int j = 0; j < myartlist.getLength(); j++) {
                        Node artchild = myartlist.item(j);
                        // If it has attributes, then it is an Element (based off API)
                        if (artchild.hasAttributes()) {
                            Element artnode = (Element) artchild;

                            String value = artnode.getTextContent().trim();
                            String name = artnode.getAttribute("name"); //NON-NLS

                            if (name.equals("ProductName")) { // NON_NLS
                                version = value;
                            } else if (name.equals("CSDVersion")) { // NON_NLS
                                // This is dependant on the fact that ProductName shows up first in the module output
                                version = version + " " + value;
                            } else if (name.equals("SystemRoot")) { //NON-NLS
                                systemRoot = value;
                            } else if (name.equals("ProductId")) { //NON-NLS
                                productId = value;
                            } else if (name.equals("RegisteredOwner")) { //NON-NLS
                                regOwner = value;
                            } else if (name.equals("RegisteredOrganization")) { //NON-NLS
                                regOrg = value;
                            } else if (name.equals("InstallDate")) { //NON-NLS
                                try {
                                    Long epochtime = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy").parse(value).getTime();
                                    installtime = epochtime;
                                    String Tempdate = installtime.toString();
                                    installtime = Long.valueOf(Tempdate) / 1000;
                                } catch (ParseException e) {
                                    logger.log(Level.SEVERE, "RegRipper::Conversion on DateTime -> ", e); //NON-NLS
                                }
                            }
                        }
                    }

                    try {
                        Collection<BlackboardAttribute> bbattributes = new ArrayList<>();
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(), parentModuleName, version));
                        if (installtime != null) {
                            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(), parentModuleName, installtime));
                        }
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PATH.getTypeID(), parentModuleName, systemRoot));
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PRODUCT_ID.getTypeID(), parentModuleName, productId));
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_OWNER.getTypeID(), parentModuleName, regOwner));
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_ORGANIZATION.getTypeID(), parentModuleName, regOrg));

                        // Check if there is already an OS_INFO artifact for this file, and add to that if possible.
                        ArrayList<BlackboardArtifact> results = tempDb.getBlackboardArtifacts(ARTIFACT_TYPE.TSK_OS_INFO, regFile.getId());
                        if (results.isEmpty()) {
                            BlackboardArtifact bbart = regFile.newArtifact(ARTIFACT_TYPE.TSK_OS_INFO);
                            bbart.addAttributes(bbattributes);
                        } else {
                            results.get(0).addAttributes(bbattributes);
                        }

                    } catch (TskCoreException ex) {
                        logger.log(Level.SEVERE, "Error adding installed program artifact to blackboard."); //NON-NLS                                    
                    }
                } else if (dataType.equals("Profiler")) { // NON-NLS

                    String os = "";
                    String procArch = "";
                    String procId = "";
                    String tempDir = "";

                    for (int j = 0; j < myartlist.getLength(); j++) {
                        Node artchild = myartlist.item(j);
                        // If it has attributes, then it is an Element (based off API)
                        if (artchild.hasAttributes()) {
                            Element artnode = (Element) artchild;

                            String value = artnode.getTextContent().trim();
                            String name = artnode.getAttribute("name"); //NON-NLS

                            if (name.equals("OS")) { // NON-NLS
                                os = value;
                            } else if (name.equals("PROCESSOR_ARCHITECTURE")) { // NON-NLS
                                procArch = value;
                            } else if (name.equals("PROCESSOR_IDENTIFIER")) {  //NON-NLS
                                procId = value;
                            } else if (name.equals("TEMP")) { //NON-NLS
                                tempDir = value;
                            }
                        }
                    }

                    try {
                        Collection<BlackboardAttribute> bbattributes = new ArrayList<>();
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_VERSION.getTypeID(), parentModuleName, os));
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PROCESSOR_ARCHITECTURE.getTypeID(), parentModuleName, procArch));
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_TEMP_DIR.getTypeID(), parentModuleName, tempDir));

                        // Check if there is already an OS_INFO artifact for this file and add to that if possible
                        ArrayList<BlackboardArtifact> results = tempDb.getBlackboardArtifacts(ARTIFACT_TYPE.TSK_OS_INFO, regFile.getId());
                        if (results.isEmpty()) {
                            BlackboardArtifact bbart = regFile.newArtifact(ARTIFACT_TYPE.TSK_OS_INFO);
                            bbart.addAttributes(bbattributes);
                        } else {
                            results.get(0).addAttributes(bbattributes);
                        }
                    } catch (TskCoreException ex) {
                        logger.log(Level.SEVERE, "Error adding os info artifact to blackboard."); //NON-NLS
                    }
                } else if (dataType.equals("CompName")) { // NON-NLS

                    String compName = "";
                    String domain = "";

                    for (int j = 0; j < myartlist.getLength(); j++) {
                        Node artchild = myartlist.item(j);
                        // If it has attributes, then it is an Element (based off API)
                        if (artchild.hasAttributes()) {
                            Element artnode = (Element) artchild;

                            String value = artnode.getTextContent().trim();
                            String name = artnode.getAttribute("name"); //NON-NLS

                            if (name.equals("ComputerName")) { // NON-NLS
                                compName = value;
                            } else if (name.equals("Domain")) { // NON-NLS
                                domain = value;
                            }
                        }
                    }

                    try {
                        Collection<BlackboardAttribute> bbattributes = new ArrayList<>();
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(), parentModuleName, compName));
                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DOMAIN.getTypeID(), parentModuleName, domain));

                        // Check if there is already an OS_INFO artifact for this file and add to that if possible
                        ArrayList<BlackboardArtifact> results = tempDb.getBlackboardArtifacts(ARTIFACT_TYPE.TSK_OS_INFO, regFile.getId());
                        if (results.isEmpty()) {
                            BlackboardArtifact bbart = regFile.newArtifact(ARTIFACT_TYPE.TSK_OS_INFO);
                            bbart.addAttributes(bbattributes);
                        } else {
                            results.get(0).addAttributes(bbattributes);
                        }
                    } catch (TskCoreException ex) {
                        logger.log(Level.SEVERE, "Error adding os info artifact to blackboard."); //NON-NLS
                    }
                } else {
                    for (int j = 0; j < myartlist.getLength(); j++) {
                        Node artchild = myartlist.item(j);
                        // If it has attributes, then it is an Element (based off API)
                        if (artchild.hasAttributes()) {
                            Element artnode = (Element) artchild;

                            String value = artnode.getTextContent().trim();
                            Collection<BlackboardAttribute> bbattributes = new ArrayList<>();

                            switch (dataType) {
                                case "recentdocs": //NON-NLS                                
                                    // BlackboardArtifact bbart = tempDb.getContentById(orgId).newArtifact(ARTIFACT_TYPE.TSK_RECENT_OBJECT);
                                    // bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_LAST_ACCESSED.getTypeID(), "RecentActivity", dataType, mtime));
                                    // bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(), "RecentActivity", dataType, mtimeItem));
                                    // bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_VALUE.getTypeID(), "RecentActivity", dataType, value));
                                    // bbart.addAttributes(bbattributes);
                                    // @@@ BC: Why are we ignoring this...
                                    break;
                                case "usb": //NON-NLS
                                    try {
                                        Long usbMtime = Long.parseLong(artnode.getAttribute("mtime")); //NON-NLS
                                        usbMtime = Long.valueOf(usbMtime.toString());

                                        BlackboardArtifact bbart = regFile.newArtifact(ARTIFACT_TYPE.TSK_DEVICE_ATTACHED);
                                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(), parentModuleName, usbMtime));
                                        String dev = artnode.getAttribute("dev"); //NON-NLS
                                        String make = "";
                                        String model = dev;
                                        if (dev.toLowerCase().contains("vid")) { //NON-NLS
                                            USBInfo info = usbMapper.parseAndLookup(dev);
                                            if (info.getVendor() != null) {
                                                make = info.getVendor();
                                            }
                                            if (info.getProduct() != null) {
                                                model = info.getProduct();
                                            }
                                        }
                                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getTypeID(), parentModuleName, make));
                                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getTypeID(), parentModuleName, model));
                                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DEVICE_ID.getTypeID(), parentModuleName, value));
                                        bbart.addAttributes(bbattributes);
                                    } catch (TskCoreException ex) {
                                        logger.log(Level.SEVERE, "Error adding device attached artifact to blackboard."); //NON-NLS
                                    }
                                    break;
                                case "uninstall": //NON-NLS
                                    Long itemMtime = null;
                                    try {
                                        Long epochtime = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy").parse(artnode.getAttribute("mtime")).getTime(); //NON-NLS
                                        itemMtime = epochtime;
                                        itemMtime = itemMtime / 1000;
                                    } catch (ParseException e) {
                                        logger.log(Level.WARNING, "Failed to parse epoch time for installed program artifact."); //NON-NLS
                                    }

                                    try {
                                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(), parentModuleName, value));
                                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(), parentModuleName, itemMtime));
                                        BlackboardArtifact bbart = regFile.newArtifact(ARTIFACT_TYPE.TSK_INSTALLED_PROG);
                                        bbart.addAttributes(bbattributes);
                                    } catch (TskCoreException ex) {
                                        logger.log(Level.SEVERE, "Error adding installed program artifact to blackboard."); //NON-NLS
                                    }
                                    break;
                                case "office": //NON-NLS
                                    String officeName = artnode.getAttribute("name"); //NON-NLS

                                    try {
                                        BlackboardArtifact bbart = regFile.newArtifact(ARTIFACT_TYPE.TSK_RECENT_OBJECT);
                                        // @@@ BC: Consider removing this after some more testing. It looks like an Mtime associated with the root key and not the individual item
                                        if (mtime != null) {
                                            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getTypeID(), parentModuleName, mtime));
                                        }
                                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(), parentModuleName, officeName));
                                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_VALUE.getTypeID(), parentModuleName, value));
                                        bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(), parentModuleName, artnode.getNodeName()));
                                        bbart.addAttributes(bbattributes);
                                    } catch (TskCoreException ex) {
                                        logger.log(Level.SEVERE, "Error adding recent object artifact to blackboard."); //NON-NLS
                                    }
                                    break;

                                case "ProcessorArchitecture": //NON-NLS
                                    // Architecture is now included under Profiler
                                    //try {
                                    //    String processorArchitecture = value;
                                    //    if (processorArchitecture.equals("AMD64"))
                                    //        processorArchitecture = "x86-64";

                                    //    BlackboardArtifact bbart = regFile.newArtifact(ARTIFACT_TYPE.TSK_OS_INFO);
                                    //    bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PROCESSOR_ARCHITECTURE.getTypeID(), parentModuleName, processorArchitecture));
                                    //    bbart.addAttributes(bbattributes);
                                    //} catch (TskCoreException ex) {
                                    //    logger.log(Level.SEVERE, "Error adding os info artifact to blackboard."); //NON-NLS
                                    //}
                                    break;

                                case "ProfileList": //NON-NLS
                                    try {

                                        String homeDir = value;
                                        String sid = artnode.getAttribute("sid"); //NON-NLS
                                        String username = artnode.getAttribute("username"); //NON-NLS

                                        BlackboardArtifact bbart = regFile.newArtifact(ARTIFACT_TYPE.TSK_OS_ACCOUNT);
                                        bbart.addAttribute(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_USER_NAME.getTypeID(),
                                                parentModuleName, username));
                                        bbart.addAttribute(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_USER_ID.getTypeID(),
                                                parentModuleName, sid));
                                        bbart.addAttribute(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PATH.getTypeID(),
                                                parentModuleName, homeDir));
                                    } catch (TskCoreException ex) {
                                        logger.log(Level.SEVERE, "Error adding account artifact to blackboard."); //NON-NLS
                                    }
                                    break;

                                case "NtuserNetwork": // NON-NLS
                                    try {
                                        String localPath = artnode.getAttribute("localPath"); //NON-NLS
                                        String remoteName = value;
                                        BlackboardArtifact bbart = regFile.newArtifact(ARTIFACT_TYPE.TSK_REMOTE_DRIVE);
                                        bbart.addAttribute(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_LOCAL_PATH.getTypeID(),
                                                parentModuleName, localPath));
                                        bbart.addAttribute(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_REMOTE_PATH.getTypeID(),
                                                parentModuleName, remoteName));

                                    } catch (TskCoreException ex) {
                                        logger.log(Level.SEVERE, "Error adding network artifact to blackboard."); //NON-NLS
                                    }
                                    break;

                                case "shellfolders": // NON-NLS
                                    // The User Shell Folders subkey stores the paths to Windows Explorer folders for the current user of the computer
                                    // (https://technet.microsoft.com/en-us/library/Cc962613.aspx).
                                    // No useful information. Skip.
                                    break;

                                default:
                                    logger.log(Level.WARNING, "Unrecognized node name: {0}", dataType); //NON-NLS
                                    break;
                            }
                        }
                    }
                }
            }
            return true;
        } catch (FileNotFoundException ex) {
            logger.log(Level.SEVERE, "Error finding the registry file."); //NON-NLS
        } catch (SAXException ex) {
            logger.log(Level.SEVERE, "Error parsing the registry XML: {0}", ex); //NON-NLS
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Error building the document parser: {0}", ex); //NON-NLS
        } catch (ParserConfigurationException ex) {
            logger.log(Level.SEVERE, "Error configuring the registry parser: {0}", ex); //NON-NLS
        } finally {
            try {
                if (fstream != null) {
                    fstream.close();
                }
            } catch (IOException ex) {
            }
        }
        return false;
    }

    @Override
    public void process(Content dataSource, IngestJobContext context) {
        this.dataSource = dataSource;
        this.context = context;
        analyzeRegistryFiles();
    }

}
