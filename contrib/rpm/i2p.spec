%global _javadir %{_datadir}/java

%if "%{_arch}" == "i386"
%define wrapper_dir linux
%endif
%if "%{_arch}" == "x86_64"
%define wrapper_dir linux64
%endif
%if "%{_arch}" == "ppc"
%define wrapper_dir linux-ppc
%endif

Name:           i2p
Version:        0.9.37
Release:        4%{?dist}
Summary:        Invisible Internet Project (I2P) - anonymous network
Conflicts:      i2pd

License:        GPL
URL:            https://geti2p.net/
Source0:        https://download.i2p2.de/releases/%version/i2psource_%version.tar.bz2

BuildRequires:  java-1.8.0-openjdk-devel 
BuildRequires:  gettext-devel 
BuildRequires:  ant
BuildRequires:  gcc
BuildRequires:  gmp-devel
BuildRequires:  systemd-units

Requires:	systemd
Requires:       java-1.8.0-openjdk
Requires(pre):  %{_sbindir}/useradd %{_sbindir}/groupadd

%if 0%{?fedora} || 0%{?rhel} >= 8
BuildRequires:	tomcat-lib 
BuildRequires:	tomcat-taglibs-standard 
BuildRequires:	gnu-getopt

Requires:	java-service-wrapper
Requires:	tomcat-lib 
Requires:	tomcat-taglibs-standard 
Requires:	gnu-getopt

Patch0:         i2p-0.9.37-fix-tomcat.patch
Patch1:         i2p-0.9.37-no-classpath.patch
%endif

%description
I2P is an anonymizing network, offering a simple layer that identity-sensitive
applications can use to securely communicate. All data is wrapped with several
layers of encryption, and the network is both distributed and dynamic, with no
trusted parties.

%global debug_package %{nil}

%prep
%setup -q
%if 0%{?fedora} || 0%{?rhel} >= 8

%if "%version" == "0.9.37" 
%patch0 -p1
%patch1 -p1
echo "without-manifest-classpath=true" >> override.properties
%endif

sed -i 's/usr\/sbin\/wrapper/usr\/sbin\/java-service-wrapper/' debian/i2p.service
echo wrapper.java.classpath.2=/usr/lib64/java-service-wrapper/wrapper.jar >> installer/resources/wrapper.config

echo "with-libtomcat8-java=true" >> override.properties
mkdir -p apps/jetty/jettylib

%if %{fedora} >= 29
cp /usr/share/java/tomcat-servlet-4.0-api.jar apps/jetty/jettylib/javax.servlet.jar
%else
cp /usr/share/java/tomcat-servlet-3.1-api.jar apps/jetty/jettylib/javax.servlet.jar
%endif

ln -s /usr/share/java/tomcat-jsp-2.3-api.jar apps/jetty/jettylib/jsp-api.jar
ln -s /usr/share/java/tomcat/jasper.jar apps/jetty/jettylib/jasper-runtime.jar
ln -s /usr/share/java/tomcat/tomcat-juli.jar apps/jetty/jettylib/commons-logging.jar
ln -s /usr/share/java/tomcat/tomcat-coyote.jar apps/jetty/jettylib/tomcat-coyote.jar
ln -s /usr/share/java/tomcat/tomcat-api.jar apps/jetty/jettylib/tomcat-api.jar
ln -s /usr/share/java/tomcat/tomcat-util.jar apps/jetty/jettylib/tomcat-util.jar
ln -s /usr/share/java/tomcat/tomcat-util-scan.jar apps/jetty/jettylib/tomcat-util-scan.jar
ln -s /usr/share/java/tomcat/jasper-el.jar apps/jetty/jettylib/jasper-el.jar
ln -s /usr/share/java/tomcat/tomcat-el-3.0-api.jar apps/jetty/jettylib/commons-el.jar

echo "with-libtaglibs-standard=true" >> override.properties
mkdir -p apps/susidns/src/lib
rm -f apps/susidns/src/lib/standard.jar
rm -f apps/susidns/src/lib/jstl.jar
rm -f apps/susidns/src/lib/jstlel.jar
ln -s /usr/share/java/tomcat-taglibs-standard/taglibs-standard-spec.jar apps/susidns/src/lib/jstl.jar 
ln -s /usr/share/java/tomcat-taglibs-standard/taglibs-standard-impl.jar apps/susidns/src/lib/standard.jar
ln -s /usr/share/java/tomcat-taglibs-standard/taglibs-standard-jstlel.jar apps/susidns/src/lib/jstlel.jar

echo "with-libgetopt-java=true" >> override.properties
mkdir -p core/java/build
ln -s /usr/share/java/gnu-getopt.jar core/java/build

%else
sed -i 's/usr\/sbin\/wrapper/usr\/bin\/i2psvc/' debian/i2p.service
%endif

sed -i '/EnvironmentFile/d' debian/i2p.service
sed -i 's/\$INSTALL_PATH/\/usr\/share\/i2p/' installer/resources/wrapper.config
echo router.updateDisabled=true >> installer/resources/router.config

%build
TZ=UTC JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF8 ant preppkg-linux-only

cd core/c/jbigi && DEBIANVERSION=9 JAVA_HOME=/usr/lib/jvm/java ./build_jbigi.sh dynamic
cd ../jcpuid && DEBIANVERSION=9 JAVA_HOME=/usr/lib/jvm/java ./build.sh

%install

install -D -m 644 %{_builddir}/%{name}-%{version}/debian/i2p.service %{buildroot}%{_unitdir}/i2p.service

#data
install -d -m 755 %{buildroot}%{_datadir}/i2p
install -D -m 644 %{_builddir}/%{name}-%{version}/history.txt %{buildroot}%{_datadir}/i2p
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/blocklist.txt %{buildroot}%{_datadir}/i2p
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/hosts.txt %{buildroot}%{_datadir}/i2p
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/*.config %{buildroot}%{_datadir}/i2p
install -d -m 755 %{buildroot}%{_datadir}/i2p/lib

%if 0%{?fedora} || 0%{?rhel} >= 8
# i2p classes
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/addressbook.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/BOB.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/desktopgui.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/i2p.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/i2psnark.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/i2ptunnel.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/jetty-i2p.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/jrobin.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/mstreaming.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/routerconsole.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/router.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/sam.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/streaming.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/systray.jar %{buildroot}%{_datadir}/i2p/lib

# i2p requires jetty v9.2 currently, fedora has v9.4
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/jetty-*.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/org.mortbay.jetty.jar %{buildroot}%{_datadir}/i2p/lib
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/org.mortbay.jmx.jar %{buildroot}%{_datadir}/i2p/lib

# Tomcat Jasper breaks Router Console, results in: JettyJasperInitializer not found 
#install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/jasper-runtime.jar %{buildroot}%{_datadir}/i2p/lib

%if %{fedora} >= 29
ln -s %{_javadir}/tomcat-servlet-4.0-api.jar %{buildroot}%{_datadir}/i2p/lib/javax.servlet.jar
%else
ln -s %{_javadir}/tomcat-servlet-3.1-api.jar %{buildroot}%{_datadir}/i2p/lib/javax.servlet.jar
%endif
ln -s %{_javadir}/tomcat-jsp-2.3-api.jar %{buildroot}%{_datadir}/i2p/lib/jsp-api.jar
ln -s %{_javadir}/tomcat/jasper.jar %{buildroot}%{_datadir}/i2p/lib/jasper-runtime.jar
ln -s %{_javadir}/tomcat/tomcat-juli.jar %{buildroot}%{_datadir}/i2p/lib/commons-logging.jar
ln -s %{_javadir}/tomcat/tomcat-coyote.jar %{buildroot}%{_datadir}/i2p/lib/tomcat-coyote.jar
ln -s %{_javadir}/tomcat/tomcat-api.jar %{buildroot}%{_datadir}/i2p/lib/tomcat-api.jar
ln -s %{_javadir}/tomcat/tomcat-util.jar %{buildroot}%{_datadir}/i2p/lib/tomcat-util.jar
ln -s %{_javadir}/tomcat/tomcat-util-scan.jar %{buildroot}%{_datadir}/i2p/lib/tomcat-util-scan.jar
ln -s %{_javadir}/tomcat/jasper-el.jar %{buildroot}%{_datadir}/i2p/lib/jasper-el.jar
ln -s %{_javadir}/tomcat/tomcat-el-3.0-api.jar %{buildroot}%{_datadir}/i2p/lib/commons-el.jar

ln -s %{_javadir}/tomcat-taglibs-standard/taglibs-standard-spec.jar %{buildroot}%{_datadir}/i2p/lib/jstl.jar
ln -s %{_javadir}/tomcat-taglibs-standard/taglibs-standard-impl.jar %{buildroot}%{_datadir}/i2p/lib/standard.jar
ln -s %{_javadir}/tomcat-taglibs-standard/taglibs-standard-jstlel.jar %{buildroot}%{_datadir}/i2p/lib/jstlel.jar

ln -s %{_javadir}/gnu-getopt.jar %{buildroot}%{_datadir}/i2p/lib/gnu-getopt.jar
%else
# if CentOS 7: just install all jar files
install -D -m 644 %{_builddir}/%{name}-%{version}/pkg-temp/lib/*.jar %{buildroot}%{_datadir}/i2p/lib
# install wrapper
install -D -m 755 %{_builddir}/%{name}-%{version}/pkg-temp/lib/wrapper/%{wrapper_dir}/i2psvc %{buildroot}%{_bindir}/i2psvc
install -D -m 755 %{_builddir}/%{name}-%{version}/pkg-temp/lib/wrapper/%{wrapper_dir}/libwrapper.so %{buildroot}%{_datadir}/i2p/lib/libwrapper.so
%endif

%{__cp} -r %{_builddir}/%{name}-%{version}/pkg-temp/certificates %{buildroot}%{_datadir}/i2p 
%{__cp} -r %{_builddir}/%{name}-%{version}/pkg-temp/docs %{buildroot}%{_datadir}/i2p 
%{__cp} -r %{_builddir}/%{name}-%{version}/pkg-temp/eepsite %{buildroot}%{_datadir}/i2p 
%{__cp} -r %{_builddir}/%{name}-%{version}/pkg-temp/geoip %{buildroot}%{_datadir}/i2p 
%{__cp} -r %{_builddir}/%{name}-%{version}/pkg-temp/locale %{buildroot}%{_datadir}/i2p 
%{__cp} -r %{_builddir}/%{name}-%{version}/pkg-temp/webapps %{buildroot}%{_datadir}/i2p 

#licenses
install -d -m 755 %{buildroot}%{_datadir}/licenses/i2p
%{__cp} -r %{_builddir}/%{name}-%{version}/pkg-temp/licenses/* %{buildroot}%{_datadir}/licenses/i2p

install -d -m 755 %{buildroot}%{_sysconfdir}/i2p
ln -s %{_datadir}/i2p/clients.config %{buildroot}%{_sysconfdir}/i2p/clients.config
ln -s %{_datadir}/i2p/i2psnark.config %{buildroot}%{_sysconfdir}/i2p/i2psnark.config
ln -s %{_datadir}/i2p/i2ptunnel.config %{buildroot}%{_sysconfdir}/i2p/i2ptunnel.config
ln -s %{_datadir}/i2p/systray.config %{buildroot}%{_sysconfdir}/i2p/systray.config
ln -s %{_datadir}/i2p/wrapper.config %{buildroot}%{_sysconfdir}/i2p/wrapper.config
    
install -d -m 755 %{buildroot}%{_libdir}/i2p
install -D -m 755 %{_builddir}/%{name}-%{version}/core/c/jbigi/*.so %{buildroot}%{_libdir}/i2p
ln -s %{_libdir}/i2p/jbigi.so %{buildroot}%{_datadir}/i2p/lib/jbigi.so
ln -s %{_libdir}/i2p/jcpuid.so %{buildroot}%{_datadir}/i2p/lib/cpuid.so

install -d -m 700 %{buildroot}%{_sharedstatedir}/i2p
install -d -m 700 %{buildroot}%{_localstatedir}/log/i2p

%pre
getent group i2psvc >/dev/null || %{_sbindir}/groupadd -r i2psvc
getent passwd i2psvc >/dev/null || \
  %{_sbindir}/useradd -r -g i2psvc -s %{_sbindir}/nologin \
                      -d %{_sharedstatedir}/i2p -c 'I2P Service' i2psvc

%post
%systemd_post i2p.service


%preun
%systemd_preun i2p.service


%postun
%systemd_postun_with_restart i2p.service


%files
# wrappers
%if 0%{?rhel} == 7
%{_bindir}/i2psvc
%endif
%{_unitdir}/i2p.service
# configs and data
%defattr(644,i2psvc,i2psvc,755)
%dir  %{_datadir}/i2p
%{_datadir}/i2p/*
%dir  %{_libdir}/i2p
%{_libdir}/i2p/*
%dir  %{_sysconfdir}/i2p
%{_sysconfdir}/i2p/*.config
# misc directories
%dir %{_datadir}/licenses/i2p
%{_datadir}/licenses/i2p/*
%dir  %{_sharedstatedir}/i2p
%dir  %{_localstatedir}/log/i2p

%changelog
* Sun Oct 14 2018 Viktor Villainov <supervillain@riseup.net> - 0.9.37-4
- prevent classpaths in jar manifests

* Mon Oct 8 2018 Viktor Villainov <supervillain@riseup.net> - 0.9.37-3
- compilation with system libraries

* Mon Oct 8 2018 Viktor Villainov <supervillain@riseup.net> - 0.9.37-2
- add native jbigi and jcpuid

* Wed Oct 3 2018 Viktor Villainov <supervillain@riseup.net> - 0.9.37-1
- bump version

* Wed Oct 3 2018 Viktor Villainov <supervillain@riseup.net> - 0.9.36-2
- make Fedora use system libraries

* Mon Sep 24 2018 Viktor Villainov <supervillain@riseup.net> - 0.9.36-1
- initial package for version 0.9.36
