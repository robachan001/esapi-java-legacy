/**
 * OWASP Enterprise Security API (ESAPI)
 * 
 * This file is part of the Open Web Application Security Project (OWASP)
 * Enterprise Security API (ESAPI) project. For details, please see
 * <a href="http://www.owasp.org/index.php/ESAPI">http://www.owasp.org/index.php/ESAPI</a>.
 *
 * Copyright (c) 2007 - The OWASP Foundation
 * 
 * The ESAPI is published by OWASP under the BSD license. You should read and accept the
 * LICENSE before you use, modify, and/or redistribute this software.
 * 
 * @author Jeff Williams <a href="http://www.aspectsecurity.com">Aspect Security</a>
 * @created 2007
 */
package org.owasp.esapi.reference;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.owasp.esapi.ESAPI;
import org.owasp.esapi.Logger;
import org.owasp.esapi.codecs.Base64;
import org.owasp.esapi.codecs.CSSCodec;
import org.owasp.esapi.codecs.Codec;
import org.owasp.esapi.codecs.HTMLEntityCodec;
import org.owasp.esapi.codecs.JavaScriptCodec;
import org.owasp.esapi.codecs.PercentCodec;
import org.owasp.esapi.codecs.PushbackString;
import org.owasp.esapi.codecs.VBScriptCodec;
import org.owasp.esapi.errors.EncodingException;
import org.owasp.esapi.errors.IntrusionException;

import sun.text.Normalizer;

/**
 * Reference implementation of the Encoder interface. This implementation takes
 * a whitelist approach to encoding, meaning that everything not specifically identified in a
 * list of "immune" characters is encoded. Several methods follow the approach in the <a
 * href="http://www.microsoft.com/downloads/details.aspx?familyid=efb9c819-53ff-4f82-bfaf-e11625130c25&displaylang=en">Microsoft
 * AntiXSS Library</a>.
 * <p>
 * The Encoder performs two key functions
 * The canonicalization algorithm is complex, as it has to be able to recognize
 * encoded characters that might affect downstream interpreters without being
 * told what encodings are possible. The stream is read one character at a time.
 * If an encoded character is encountered, it is canonicalized and pushed back
 * onto the stream. If the next character is encoded, then a intrusion exception
 * is thrown for the double-encoding which is assumed to be an attack.
 * <p>
 * The encoding methods also attempt to prevent double encoding, by canonicalizing strings
 * that are passed to them for encoding.
 * <p>
 * Currently the implementation supports:
 * <ul>
 * <li>HTML Entity Encoding (including non-terminated)</li>
 * <li>Percent Encoding</li>
 * <li>Backslash Encoding</li>
 * </ul>
 * 
 * @author Jeff Williams (jeff.williams .at. aspectsecurity.com) <a
 *         href="http://www.aspectsecurity.com">Aspect Security</a>
 * @since June 1, 2007
 * @see org.owasp.esapi.Encoder
 */
public class DefaultEncoder implements org.owasp.esapi.Encoder {

	// Codecs
	List codecs = new ArrayList();
	private HTMLEntityCodec htmlCodec = new HTMLEntityCodec();
	private PercentCodec percentCodec = new PercentCodec();
	private JavaScriptCodec javaScriptCodec = new JavaScriptCodec();
	private VBScriptCodec vbScriptCodec = new VBScriptCodec();
	private CSSCodec cssCodec = new CSSCodec();
	
	/** The logger. */
	private final Logger logger = ESAPI.getLogger("Encoder");
	
	/** Character sets that define characters immune from encoding in various formats */
	private final static char[] IMMUNE_HTML = { ',', '.', '-', '_', ' ' };
	private final static char[] IMMUNE_HTMLATTR = { ',', '.', '-', '_' };
	private final static char[] IMMUNE_CSS = { ' ' };  // TODO: check
	private final static char[] IMMUNE_JAVASCRIPT = { ',', '.', '-', '_', ' ' };
	private final static char[] IMMUNE_VBSCRIPT = { ' ' };  // TODO: check
	private final static char[] IMMUNE_XML = { ',', '.', '-', '_', ' ' };
	private final static char[] IMMUNE_SQL = { ' ' };
	private final static char[] IMMUNE_XMLATTR = { ',', '.', '-', '_' };
	private final static char[] IMMUNE_XPATH = { ',', '.', '-', '_', ' ' };

	/** Standard character sets */
	public final static char[] CHAR_LOWERS = { 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z' };
	public final static char[] CHAR_UPPERS = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z' };
	public final static char[] CHAR_DIGITS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' };
	public final static char[] CHAR_SPECIALS = { '.', '-', '_', '!', '@', '$', '^', '*', '=', '~', '|', '+', '?' };
	public final static char[] CHAR_LETTERS = union(CHAR_LOWERS, CHAR_UPPERS);
	public final static char[] CHAR_ALPHANUMERICS = union(CHAR_LETTERS, CHAR_DIGITS);

	/**
	 * Password character set, is alphanumerics (without l, i, I, o, O, and 0)
	 * selected specials like + (bad for URL encoding, | is like i and 1,
	 * etc...)
	 */
	final static char[] CHAR_PASSWORD_LOWERS = { 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'j', 'k', 'm', 'n', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z' };
	final static char[] CHAR_PASSWORD_UPPERS = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z' };
	final static char[] CHAR_PASSWORD_DIGITS = { '2', '3', '4', '5', '6', '7', '8', '9' };
	final static char[] CHAR_PASSWORD_SPECIALS = { '_', '.', '!', '@', '$', '*', '=', '-', '?' };
	public final static char[] CHAR_PASSWORD_LETTERS = union( CHAR_PASSWORD_LOWERS, CHAR_PASSWORD_UPPERS );


	public DefaultEncoder() {
		// initialize the codec list to use for canonicalization
		codecs.add( htmlCodec );
		codecs.add( percentCodec );
		codecs.add( javaScriptCodec );

		// leave this out because it eats / characters
		// codecs.add( cssCodec );

		// leave this out because it eats " characters
		// codecs.add( vbScriptCodec );
		
		Arrays.sort( DefaultEncoder.IMMUNE_HTML );
		Arrays.sort( DefaultEncoder.IMMUNE_HTMLATTR );
		Arrays.sort( DefaultEncoder.IMMUNE_JAVASCRIPT );
		Arrays.sort( DefaultEncoder.IMMUNE_VBSCRIPT );
		Arrays.sort( DefaultEncoder.IMMUNE_XML );
		Arrays.sort( DefaultEncoder.IMMUNE_XMLATTR );
		Arrays.sort( DefaultEncoder.IMMUNE_XPATH );
		Arrays.sort( DefaultEncoder.CHAR_LOWERS );
		Arrays.sort( DefaultEncoder.CHAR_UPPERS );
		Arrays.sort( DefaultEncoder.CHAR_DIGITS );
		Arrays.sort( DefaultEncoder.CHAR_SPECIALS );
		Arrays.sort( DefaultEncoder.CHAR_LETTERS );
		Arrays.sort( DefaultEncoder.CHAR_ALPHANUMERICS );
		Arrays.sort( DefaultEncoder.CHAR_PASSWORD_LOWERS );
		Arrays.sort( DefaultEncoder.CHAR_PASSWORD_UPPERS );
		Arrays.sort( DefaultEncoder.CHAR_PASSWORD_DIGITS );
		Arrays.sort( DefaultEncoder.CHAR_PASSWORD_SPECIALS );
		Arrays.sort( DefaultEncoder.CHAR_PASSWORD_LETTERS );
	}

	/**
	 * Simplifies encoded characters to their
	 * simplest form so that they can be properly validated. Attackers
	 * frequently use encoding schemes to disguise their attacks and bypass
	 * validation routines.
	 * 
	 * Handling multiple encoding schemes simultaneously is difficult, and
	 * requires some special consideration. In particular, the problem of
	 * double-encoding is difficult for parsers, and combining several encoding
	 * schemes in double-encoding makes it even harder. Consider decoding
	 * 
	 * <PRE>
	 * &amp;lt;
	 * </PRE>
	 * 
	 * or
	 * 
	 * <PRE>
	 * %26lt;
	 * </PRE>
	 * 
	 * or
	 * 
	 * <PRE>
	 * &amp;lt;
	 * </PRE>.
	 * 
	 * This implementation disallows ALL double-encoded characters and throws an
	 * IntrusionException when they are detected. Also, named entities that are
	 * not known are simply removed.
	 * 
	 * Note that most data from the browser is likely to be encoded with URL
	 * encoding (RFC 3986). The web server will decode the URL and form data
	 * once, so most encoded data received in the application must have been
	 * double-encoded by the attacker. However, some HTTP inputs are not decoded
	 * by the browser, so this routine allows a single level of decoding.
	 * 
	 * @throws IntrusionException
	 * @see org.owasp.esapi.Validator#canonicalize(java.lang.String)
	 */
	public String canonicalize( String input ) {
		if ( input == null ) return null;
		return canonicalize( input, true );
	}
	
	/**
	 * Strict mode throws an exception when any double encoded data is detected.
	 */
	public String canonicalize( String input, boolean strict ) {
		if ( input == null ) return null;
		String candidate = canonicalizeOnce( input );
		String canary = canonicalizeOnce( candidate );
		if ( !candidate.equals( canary ) ) {
			if ( strict ) {
				throw new IntrusionException( "Input validation failure", "Double encoding detected in " + input );
			} else {
				logger.warning( Logger.SECURITY, "Double encoding detected in " + input );
			}
		}
		return candidate;
	}
	
	private String canonicalizeOnce( String input ) {
		if ( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		PushbackString pbs = new PushbackString( input );
		while ( pbs.hasNext() ) {
			// test for encoded character and pushback if found
			boolean encoded = decodeNext( pbs );

			// get the next character and do something with it
			Character ch = pbs.next();
			
			// if an encoded character is found, push it back
			if ( encoded ) {
				pbs.pushback( ch );
			} else {
				sb.append( ch );
			}
		}
		return sb.toString();
	}
	
	/**
	 * Helper method to iterate through codecs to see if the current character
	 * is an encoded character in any of them. If the current character is
	 * encoded, then it is decoded and pushed back onto the string, and this
	 * method returns true.  If the current character is not encoded, then the
	 * pushback stream is reset to its original state and this method returns false.
	 */
	private boolean decodeNext( PushbackString pbs ) {
		Iterator i = codecs.iterator();
		pbs.mark();
		while ( i.hasNext() ) {
			pbs.reset();
			Codec codec = (Codec)i.next();
			Character decoded = codec.decodeCharacter(pbs);
			if ( decoded != null ) {
				pbs.pushback( decoded );
				return true;
			}
		}
		pbs.reset();
		return false;
	}


	/**
	 * Normalizes special characters down to ASCII using the Normalizer built
	 * into Java. Note that this method may introduce security issues if
	 * characters are normalized into special characters that have meaning
	 * to the destination of the data.
	 * 
	 * @see org.owasp.esapi.Validator#normalize(java.lang.String)
	 */
	public String normalize(String input) {
		// Split any special characters into two parts, the base character and
		// the modifier
		
        String separated = Normalizer.normalize(input, Normalizer.DECOMP, 0);  // Java 1.4
		// String separated = Normalizer.normalize(input, Form.NFD);   // Java 1.6

		// remove any character that is not ASCII
		return separated.replaceAll("[^\\p{ASCII}]", "");
	}


	/**
	 * Encoding utility method. It is strongly recommended that you
	 * canonicalize input before calling this method to prevent double-encoding.
	 */
	private String encode( char c, Codec codec, char[] baseImmune, char[] specialImmune ) {
		if (isContained(baseImmune, c) || isContained(specialImmune, c)) {
			return ""+c;
		} else {
			return codec.encodeCharacter( new Character( c ) );
		}
	}

	
	
	/**
	 * Encode the input string using HTML entity encoding.  Note that the following characters:
	 * 00�08, 0B�0C, 0E�1F, and 7F�9F Cannot be used in HTML. See http://en.wikipedia.org/wiki/Character_encodings_in_HTML
	 * for more information.
	 * See the SGML declaration - http://www.w3.org/TR/html4/sgml/sgmldecl.html
	 * See the XML specification - see http://www.w3.org/TR/REC-xml/#charsets
	 * @see org.owasp.esapi.Encoder#encodeForHTML(java.lang.String)
	 */
	public String encodeForHTML(String input) {
	    if( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		for ( int i=0; i<input.length(); i++ ) {
			char c = input.charAt(i);
			if ( c == '\t' || c == '\n' || c == '\r' ) {
				sb.append( c );
			} else if ( c <= 0x1f || ( c >= 0x7f && c <= 0x9f ) ) {
				logger.warning( Logger.SECURITY, "Attempt to HTML entity encode illegal character: " + (int)c + " (skipping)" );
			} else {
				sb.append( encode( c, htmlCodec, CHAR_ALPHANUMERICS, IMMUNE_HTML ) );
			}
		}
		return sb.toString();
	 }
	 
	 
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#encodeForHTMLAttribute(java.lang.String)
	 */
	public String encodeForHTMLAttribute(String input) {
	    if( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		for ( int i=0; i<input.length(); i++ ) {
			char c = input.charAt(i);
			sb.append( encode( c, htmlCodec, CHAR_ALPHANUMERICS, IMMUNE_HTMLATTR ) );
		}
		return sb.toString();
	}

	
	/**
	 * http://www.w3.org/TR/CSS21/syndata.html#escaped-characters
	 */
	public String encodeForCSS(String input) {
	    if( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		for ( int i=0; i<input.length(); i++ ) {
			char c = input.charAt(i);
			if ( c != 0 ) {
				sb.append( encode( c, cssCodec, CHAR_ALPHANUMERICS, IMMUNE_CSS ) );
			}
		}
		return sb.toString();
	}

	
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#encodeForJavaScript(java.lang.String)
	 */
	public String encodeForJavaScript(String input) {
	    if( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		for ( int i=0; i<input.length(); i++ ) {
			char c = input.charAt(i);
			sb.append( encode( c, javaScriptCodec, CHAR_ALPHANUMERICS, IMMUNE_JAVASCRIPT ) );
		}
		return sb.toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#encodeForVisualBasicScript(java.lang.String)
	 */
	public String encodeForVBScript(String input) {
	    if( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		for ( int i=0; i<input.length(); i++ ) {
			char c = input.charAt(i);
			sb.append( encode( c, vbScriptCodec, CHAR_ALPHANUMERICS, IMMUNE_VBSCRIPT ) );
		}
		return sb.toString();
	}

	/**
	 * This method is not recommended. The use PreparedStatement is the normal
	 * and preferred approach. However, if for some reason this is impossible,
	 * then this method is provided as a weaker alternative. The best approach
	 * is to make sure any single-quotes are double-quoted. Another possible
	 * approach is to use the {escape} syntax described in the JDBC
	 * specification in section 1.5.6 (see
	 * http://java.sun.com/j2se/1.4.2/docs/guide/jdbc/getstart/statement.html).
	 * However, this syntax does not work with all drivers, and requires
	 * modification of all queries.
	 * 
	 * @param input
	 *            the input
	 * @return the string
	 * @see org.owasp.esapi.Encoder#encodeForSQL(java.lang.String)
	 */
	public String encodeForSQL(Codec codec, String input) {
	    if( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		for ( int i=0; i<input.length(); i++ ) {
			char c = input.charAt(i);
			sb.append( encode( c, codec, CHAR_ALPHANUMERICS, IMMUNE_SQL ) );
		}
		return sb.toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#encodeForLDAP(java.lang.String)
	 */
	public String encodeForLDAP(String input) {
		// TODO: replace with LDAP codec
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			switch (c) {
			case '\\':
				sb.append("\\5c");
				break;
			case '*':
				sb.append("\\2a");
				break;
			case '(':
				sb.append("\\28");
				break;
			case ')':
				sb.append("\\29");
				break;
			case '\0':
				sb.append("\\00");
				break;
			default:
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#encodeForDN(java.lang.String)
	 */
	public String encodeForDN(String input) {
		StringBuffer sb = new StringBuffer();
		if ((input.length() > 0) && ((input.charAt(0) == ' ') || (input.charAt(0) == '#'))) {
			sb.append('\\'); // add the leading backslash if needed
		}
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			switch (c) {
			case '\\':
				sb.append("\\\\");
				break;
			case ',':
				sb.append("\\,");
				break;
			case '+':
				sb.append("\\+");
				break;
			case '"':
				sb.append("\\\"");
				break;
			case '<':
				sb.append("\\<");
				break;
			case '>':
				sb.append("\\>");
				break;
			case ';':
				sb.append("\\;");
				break;
			default:
				sb.append(c);
			}
		}
		// add the trailing backslash if needed
		if ((input.length() > 1) && (input.charAt(input.length() - 1) == ' ')) {
			sb.insert(sb.length() - 1, '\\');
		}
		return sb.toString();
	}

	/**
	 * This implementation encodes almost everything and may overencode. The
	 * difficulty is that XPath has no built in mechanism for escaping
	 * characters. It is possible to use XQuery in a parameterized way to
	 * prevent injection. For more information, refer to <a
	 * href="http://www.ibm.com/developerworks/xml/library/x-xpathinjection.html">this
	 * article</a> which specifies the following list of characters as the most
	 * dangerous: ^&"*';<>(). <a
	 * href="http://www.packetstormsecurity.org/papers/bypass/Blind_XPath_Injection_20040518.pdf">This
	 * paper</a> suggests disallowing ' and " in queries.
	 * 
	 * @param input
	 *            the input
	 * @return the string
	 * @see org.owasp.esapi.Encoder#encodeForXPath(java.lang.String)
	 */
	public String encodeForXPath(String input) {
	    if( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		for ( int i=0; i<input.length(); i++ ) {
			char c = input.charAt(i);
			sb.append( encode( c, htmlCodec, CHAR_ALPHANUMERICS, IMMUNE_XPATH ) );
		}
		return sb.toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#encodeForXML(java.lang.String)
	 */
	public String encodeForXML(String input) {
	    if( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		for ( int i=0; i<input.length(); i++ ) {
			char c = input.charAt(i);
			sb.append( encode( c, htmlCodec, CHAR_ALPHANUMERICS, IMMUNE_XML ) );
		}
		return sb.toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#encodeForXMLAttribute(java.lang.String)
	 */
	public String encodeForXMLAttribute(String input) {
	    if( input == null ) return null;
		StringBuffer sb = new StringBuffer();
		for ( int i=0; i<input.length(); i++ ) {
			char c = input.charAt(i);
			sb.append( encode( c, htmlCodec, CHAR_ALPHANUMERICS, IMMUNE_XMLATTR ) );
		}
		return sb.toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#encodeForURL(java.lang.String)
	 */
	public String encodeForURL(String input) throws EncodingException {
		try {
			return URLEncoder.encode(input, ESAPI.securityConfiguration().getCharacterEncoding());
		} catch (UnsupportedEncodingException ex) {
			throw new EncodingException("Encoding failure", "Encoding not supported", ex);
		} catch (Exception e) {
			throw new EncodingException("Encoding failure", "Problem URL decoding input", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#decodeFromURL(java.lang.String)
	 */
	public String decodeFromURL(String input) throws EncodingException {
		String canonical = canonicalize(input);
		try {
			return URLDecoder.decode(canonical, ESAPI.securityConfiguration().getCharacterEncoding());
		} catch (UnsupportedEncodingException ex) {
			throw new EncodingException("Decoding failed", "Encoding not supported", ex);
		} catch (Exception e) {
			throw new EncodingException("Decoding failed", "Problem URL decoding input", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#encodeForBase64(byte[])
	 */
	public String encodeForBase64(byte[] input, boolean wrap) {
		int options = 0;
		if ( !wrap ) {
			options |= Base64.DONT_BREAK_LINES;
		}
		return Base64.encodeBytes(input, options);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.owasp.esapi.Encoder#decodeFromBase64(java.lang.String)
	 */
	public byte[] decodeFromBase64(String input) throws IOException {
		return Base64.decode( input );
	}

    /**
     * Union two character arrays.
     * 
     * @param c1 the c1
     * @param c2 the c2
     * @return the char[]
     */
    private static char[] union(char[] c1, char[] c2) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < c1.length; i++) {
            if (!contains(sb, c1[i]))
                sb.append(c1[i]);
        }
        for (int i = 0; i < c2.length; i++) {
            if (!contains(sb, c2[i]))
                sb.append(c2[i]);
        }
        char[] c3 = new char[sb.length()];
        sb.getChars(0, sb.length(), c3, 0);
        Arrays.sort(c3);
        return c3;
    }

    /**
     * Returns true if the character is contained in the provided StringBuffer.
     */
    private static boolean contains(StringBuffer haystack, char c) {
        for (int i = 0; i < haystack.length(); i++) {
            if (haystack.charAt(i) == c)
                return true;
        }
        return false;
    }
    

	
	/**
	 * Returns true if the character is contained in the provided array of characters.
	 */
	protected boolean isContained(char[] haystack, char c) {
		for (int i = 0; i < haystack.length; i++) {
			if (c == haystack[i])
				return true;
		}
		return false;
		
		// If sorted arrays are guaranteed, this is faster
		// return( Arrays.binarySearch(array, element) >= 0 );
	}


    
}