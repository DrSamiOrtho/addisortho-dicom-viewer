<#--
  Template of resources/legal/THIRD-PARTY-NOTICES.txt, rendered at build time by the
  license-maven-plugin (see the root pom.xml). Part A below is written by hand for what Maven
  cannot see; part B is generated from the dependencies of the reactor. Keep this file ASCII and
  avoid dollar signs and angle brackets in the text, which Freemarker interprets.
  Context: licenseMap is a collection of entries (key: licence name, value: the projects using it).
-->
<#-- Modules of the Weasis project itself are covered by the first paragraph, not listed again.
     EasyMock and Objenesis reach the reactor only as test-support dependencies of
     org.apache.felix.prefs; they are not part of the product. -->
<#function isHidden p>
    <#return (p.groupId?starts_with("org.weasis.") && !p.groupId?starts_with("org.weasis.thirdparty"))
        || p.groupId == "org.easymock" || p.groupId == "org.objenesis">
</#function>
<#function artifactFormat p>
    <#if p.name?index_of('Unnamed') &gt; -1>
        <#return p.artifactId + " (" + p.groupId + ":" + p.artifactId + ":" + p.version + " - " + (p.url!"no url defined") + ")">
    <#else>
        <#return p.name + " (" + p.groupId + ":" + p.artifactId + ":" + p.version + " - " + (p.url!"no url defined") + ")">
    </#if>
</#function>
ADDIS ORTHO DICOM VIEWER - THIRD-PARTY NOTICES
==============================================

Addis Ortho DICOM Viewer is a modified version of Weasis
(https://github.com/nroduit/Weasis), Copyright 2009-2026 Weasis Team, used under the
Apache License 2.0. Addis Ortho Trading PLC changed the application name, logos, icons,
installer identity, help menu and licence notices (see the NOTICE file of the source
distribution). The licence of the viewer itself is in the file Licence.txt of the installer
and in licenses/Apache-2.0.txt next to this file. The Weasis modules and libraries
(weasis-core, weasis-core-img, weasis-dicom-codec, and the other org.weasis artifacts) are
under the same terms and are not listed again below.

The viewer includes the third-party software listed below. Each component remains under its
own licence. The full texts of the licences that need one are in the folder "licenses" next
to this file (Apache-2.0, EPL-2.0, LGPL-2.1, LGPL-3.0 with GPL-3.0, and GPL-2.0 with the
Classpath Exception).

Where a component is offered under several licences, this distribution uses it under the
first licence that applies from this list: Apache-2.0, EPL-2.0, EDL-1.0 or BSD, LGPL.
Libraries under the LGPL (version 2.1 or 3, as each project states) are included as separate
library files, in binary form, as published by their authors or by the Weasis project. You
may replace them with a modified version of the same library under the terms of the LGPL;
their source code is available from the project addresses given below.


A. COMPONENTS THAT THE BUILD DOES NOT LIST
------------------------------------------

A1. Java runtime
    The installers include a Java runtime built from Eclipse Temurin (OpenJDK), published by
    the Eclipse Adoptium project, https://adoptium.net.
    Licence: GNU General Public License version 2 with the Classpath Exception
    (licenses/GPL-2.0-with-Classpath-Exception.txt). The notices of the runtime itself are in
    the "legal" folder of the bundled Java runtime. Source code: https://github.com/adoptium

A2. OpenCV and the image codecs in the native library
    Image processing and DICOM image decoding use a native library based on OpenCV, built by
    the Weasis project. Its Java wrapper is weasis-core-img,
    https://github.com/nroduit/weasis-core-img (EPL-2.0 or Apache-2.0).
    - OpenCV, Apache-2.0, https://opencv.org/license/
    - DICOM support in OpenCV, by Nicolas Roduit, Apache-2.0 (same licence as OpenCV)
    - OpenJPEG, BSD licence, https://github.com/uclouvain/openjpeg
    - CharLS, BSD licence, https://github.com/team-charls/charls
    - IJG libjpeg version 6b, below.

    This software is based in part on the work of the Independent JPEG Group.

    The IJG licence, as required for the libjpeg code in the native library:

      The authors make NO WARRANTY or representation, either express or implied,
      with respect to this software, its quality, accuracy, merchantability, or
      fitness for a particular purpose. This software is provided "AS IS", and you,
      its user, assume the entire risk as to its quality and accuracy.

      This software is copyright (C) 1991-1998, Thomas G. Lane.
      All Rights Reserved except as specified below.

      Permission is hereby granted to use, copy, modify, and distribute this
      software (or portions thereof) for any purpose, without fee, subject to these
      conditions:
      (1) If any part of the source code for this software is distributed, then this
      README file must be included, with this copyright and no-warranty notice
      unaltered; and any additions, deletions, or changes to the original files
      must be clearly indicated in accompanying documentation.
      (2) If only executable code is distributed, then the accompanying
      documentation must state that "this software is based in part on the work of
      the Independent JPEG Group".
      (3) Permission for use of this software is granted only if the user accepts
      full responsibility for any undesirable consequences; the authors accept
      NO LIABILITY for damages of any kind.

      These conditions apply to any software derived from or based on the IJG code,
      not just to the unmodified library. If you use our work, you ought to
      acknowledge us.

      Permission is NOT granted for the use of any IJG author's name or company name
      in advertising or publicity relating to this software or products derived from
      it. This software may be referred to only as "the Independent JPEG Group's
      software".

      We specifically permit and encourage the use of this software as the basis of
      commercial products, provided that all warranty or liability claims are
      assumed by the product vendor.

A3. JOGL native libraries (3D viewer)
    The 3D viewer uses the JogAmp libraries (JOGL, GlueGen, JOCL) and their native libraries
    for Windows and macOS, https://jogamp.org. Licence: BSD licence (2-clause), with some files
    under other permissive licences; see https://jogamp.org for the details. The Java parts
    are also listed in part B.

A4. Icons
    - Icons from the JetBrains IntelliJ Platform icon set, Apache-2.0,
      https://jetbrains.design/intellij/resources/icons_list/
    - Icons by Icons8 (We love SVG), MIT licence, https://github.com/icons8/welovesvg
    - Other icons and resources of the Weasis project, Apache-2.0.


B. JAVA LIBRARIES AND OSGI BUNDLES (generated at build time, grouped by licence)
--------------------------------------------------------------------------------

<#if licenseMap?size == 0>
The build found no dependencies.
<#else>
    <#list licenseMap as e>
        <#assign license = e.getKey()/>
        <#assign shown = e.getValue()?filter(p -> !isHidden(p))?sequence/>
        <#if shown?size &gt; 0>

${license}

            <#list shown as project>
  * ${artifactFormat(project)}
            </#list>
        </#if>
    </#list>
</#if>
