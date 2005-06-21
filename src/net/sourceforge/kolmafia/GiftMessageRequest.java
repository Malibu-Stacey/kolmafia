/**
 * Copyright (c) 2005, KoLmafia development team
 * http://kolmafia.sourceforge.net/
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  [1] Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *  [2] Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in
 *      the documentation and/or other materials provided with the
 *      distribution.
 *  [3] Neither the name "KoLmafia development team" nor the names of
 *      its contributors may be used to endorse or promote products
 *      derived from this software without specific prior written
 *      permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package net.sourceforge.kolmafia;
import net.java.dev.spellcast.utilities.LockableListModel;

/**
 * An extension of a <code>KoLRequest</code> which specifically handles
 * donating to the Hall of the Legends of the Times of Old.
 */

public class GiftMessageRequest extends KoLRequest
{
	private String recipient, outsideMessage, insideMessage;
	private GiftWrapper wrappingType;
	private int maxCapacity, materialCost;

	public static final LockableListModel PACKAGES = new LockableListModel();
	static
	{
		PACKAGES.add( new GiftWrapper( "plain brown wrapper", 1, 1, 0 ) );
		PACKAGES.add( new GiftWrapper( "less-than-three-shaped box", 2, 2, 100 ) );
	}

	private static class GiftWrapper
	{
		private StringBuffer name;
		private int radio, maxCapacity, materialCost;

		public GiftWrapper( String name, int radio, int maxCapacity, int materialCost )
		{
			this.radio = radio;
			this.maxCapacity = maxCapacity;
			this.materialCost = materialCost;

			this.name = new StringBuffer();
			this.name.append( name );
			this.name.append( " - " );
			this.name.append( materialCost );
			this.name.append( " meat (" );
			this.name.append( maxCapacity );
			this.name.append( " item" );

			if ( maxCapacity > 1 )
				this.name.append( 's' );

			this.name.append( ')' );
		}

		public String toString()
		{	return name.toString();
		}
	}

	public static final int PLAIN_BROWN_WRAPPER = 1;
	public static final int LESS_THAN_THREE_SHAPED_BOX = 2;

	private static final int [] CAPACITIES = { 0, 1, 2 };
	private static final int [] MATERIAL_COST = { 0, 0, 100 };

	public GiftMessageRequest( KoLmafia client, String recipient, String outsideMessage, String insideMessage,
		Object wrappingType, Object [] attachments, int meatAttachment )
	{
		super( client, "town_sendgift.php" );
		addFormField( "pwd", client.getPasswordHash() );
		addFormField( "action", "Yep." );
		addFormField( "towho", recipient );
		addFormField( "note", outsideMessage );
		addFormField( "insidenote", insideMessage );

		this.recipient = client.getMessenger() == null ? recipient : client.getPlayerID( recipient );
		this.outsideMessage = outsideMessage;
		this.insideMessage = insideMessage;

		this.wrappingType = (GiftWrapper) wrappingType;
		this.maxCapacity = this.wrappingType.maxCapacity;
		this.materialCost = this.wrappingType.materialCost;

		addFormField( "whichpackage", String.valueOf( this.wrappingType.radio ) );
		addFormField( "sendmeat", String.valueOf( meatAttachment ) );
	}

	protected int getCapacity()
	{	return maxCapacity;
	}

	protected void repeat( Object [] attachments )
	{	(new GiftMessageRequest( client, recipient, outsideMessage, insideMessage, wrappingType, attachments, 0 )).run();
	}

	protected String getSuccessMessage()
	{	return "<td>Package sent.</td>";
	}


	/**
	 * Runs the request.  Note that this does not report an error if it fails;
	 * it merely parses the results to see if any gains were made.
	 */

	public void run()
	{
		// Once all the form fields are broken up, this
		// just calls the normal run method from KoLRequest
		// to execute the request.

		super.run();

		// If an error state occurred, return from this
		// request, since there's no content to parse

		if ( isErrorState || responseCode != 200 )
			return;

		// With that done, the client needs to be updated
		// on the package sending costs.

		if ( responseText.indexOf( getSuccessMessage() ) != -1 )
			client.processResult( new AdventureResult( AdventureResult.MEAT, 0 - materialCost ) );
	}
}
