package com.sun.corba.se.PortableActivationIDL;

/**
* com/sun/corba/se/PortableActivationIDL/ServerAlreadyInstalledHolder.java .
* Generated by the IDL-to-Java compiler (portable), version "3.2"
* from t:/workspace/corba/src/java.corba/share/classes/com/sun/corba/se/PortableActivationIDL/activation.idl
* Tuesday, December 19, 2017 6:18:15 PM PST
*/

public final class ServerAlreadyInstalledHolder implements org.omg.CORBA.portable.Streamable
{
  public com.sun.corba.se.PortableActivationIDL.ServerAlreadyInstalled value = null;

  public ServerAlreadyInstalledHolder ()
  {
  }

  public ServerAlreadyInstalledHolder (com.sun.corba.se.PortableActivationIDL.ServerAlreadyInstalled initialValue)
  {
    value = initialValue;
  }

  public void _read (org.omg.CORBA.portable.InputStream i)
  {
    value = com.sun.corba.se.PortableActivationIDL.ServerAlreadyInstalledHelper.read (i);
  }

  public void _write (org.omg.CORBA.portable.OutputStream o)
  {
    com.sun.corba.se.PortableActivationIDL.ServerAlreadyInstalledHelper.write (o, value);
  }

  public org.omg.CORBA.TypeCode _type ()
  {
    return com.sun.corba.se.PortableActivationIDL.ServerAlreadyInstalledHelper.type ();
  }

}
