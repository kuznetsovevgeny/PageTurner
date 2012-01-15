/*
 * Copyright (C) 2011 Alex Kuiper
 * 
 * This file is part of PageTurner
 *
 * PageTurner is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PageTurner is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PageTurner.  If not, see <http://www.gnu.org/licenses/>.*
 */
package net.nightwhistler.pageturner.activity;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.nightwhistler.pageturner.R;
import net.nightwhistler.pageturner.library.LibraryBook;
import net.nightwhistler.pageturner.library.LibraryService;
import net.nightwhistler.pageturner.library.QueryResult;
import net.nightwhistler.pageturner.library.QueryResultAdapter;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.epub.EpubReader;
import nl.siegmann.epublib.service.MediatypeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import roboguice.activity.RoboActivity;
import roboguice.inject.InjectResource;
import roboguice.inject.InjectView;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.inject.Inject;

public class LibraryActivity extends RoboActivity implements OnItemClickListener  {
	
	@Inject 
	private LibraryService libraryService;
	
	@InjectView(R.id.librarySpinner)
	private Spinner spinner;
	
	@InjectView(R.id.libraryList)
	private ListView listView;
	
	@InjectResource(R.drawable.river_diary)
	private Drawable backupCover;
		
	private static enum Selections {
		 BY_LAST_READ, LAST_ADDED, UNREAD, BY_TITLE, BY_AUTHOR;
	}	
	
	private BookAdapter bookAdapter;
		
	private static final DateFormat DATE_FORMAT = DateFormat.getDateInstance(DateFormat.LONG);
	
	private ProgressDialog waitDialog;
	private ProgressDialog importDialog;	
	
	private SharedPreferences settings;
	
	private Selections lastSelection = Selections.LAST_ADDED;
	
	private static final Logger LOG = LoggerFactory.getLogger(LibraryActivity.class); 
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		
		super.onCreate(savedInstanceState);
		setContentView(R.layout.library_menu);
		
		this.bookAdapter = new BookAdapter(this);
		this.listView.setAdapter(bookAdapter);
		this.listView.setOnItemClickListener(this);
		
		this.settings = PreferenceManager.getDefaultSharedPreferences(this); 
				
		ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
		            this, R.array.libraryQueries, android.R.layout.simple_spinner_item);
		
		spinner.setAdapter(adapter);
		spinner.setOnItemSelectedListener(new MenuSelectionListener());
		spinner.setSelection(lastSelection.ordinal());
						
		this.waitDialog = new ProgressDialog(this);
		this.waitDialog.setOwnerActivity(this);
		
		this.importDialog = new ProgressDialog(this);
		this.importDialog.setOwnerActivity(this);
		
		registerForContextMenu(this.listView);	
	}
	
	@Override
	public void onItemClick(AdapterView<?> arg0, View arg1, int pos, long arg3) {
		
		LibraryBook book = this.bookAdapter.getResultAt(pos);
		Intent intent = new Intent(this, ReadingActivity.class);
		
		intent.setData( Uri.parse(book.getFileName()));
		this.setResult(RESULT_OK, intent);
				
		startActivityIfNeeded(intent, 99);
	}
	
	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
		final LibraryBook selectedBook = bookAdapter.getResultAt(info.position);
		
		MenuItem detailsItem = menu.add( "View details");
		
		detailsItem.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				Intent intent = new Intent( LibraryActivity.this, BookDetailsActivity.class );
				intent.putExtra("book", selectedBook.getFileName());				
				startActivity(intent);					
				return true;
			}
		});
		
		MenuItem deleteItem = menu.add("Delete from library");
		
		deleteItem.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				libraryService.deleteBook( selectedBook.getFileName() );
				new LoadBooksTask().execute(lastSelection);
				return true;					
			}
		});				
		
	}	
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		
		MenuItem prefs = menu.add("Preferences");
		prefs.setIcon( getResources().getDrawable(R.drawable.cog) );
		
		prefs.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				Intent intent = new Intent(LibraryActivity.this, PageTurnerPrefsActivity.class);
				startActivity(intent);
				
				return true;
			}
		});
		
		MenuItem item = menu.add("Scan for books");
		item.setIcon( getResources().getDrawable(R.drawable.book_refresh) );
		
		item.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				Intent intent = new Intent("org.openintents.action.PICK_DIRECTORY");

				try {
					startActivityForResult(intent, 1);
				} catch (ActivityNotFoundException e) {
					new ImportBooksTask().execute(new File("/sdcard"));  
				}

				return true;
			}
		});		
		
		return true;
	}	
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

    	if ( resultCode == RESULT_OK && data != null) {
    		// obtain the filename
    		Uri fileUri = data.getData();
    		if (fileUri != null) {
    			String filePath = fileUri.getPath();
    			if (filePath != null) {
    				new ImportBooksTask().execute(new File(filePath));    				
    			}
    		}
    	}	
	}	
	
	@Override
	protected void onStop() {		
		this.libraryService.close();	
		this.waitDialog.dismiss();
		this.importDialog.dismiss();
		super.onStop();
	}
	
	@Override
	public void onBackPressed() {
		finish();			
	}	
	
	@Override
	protected void onPause() {
		
		this.bookAdapter.clear();
		this.libraryService.close();
		//We clear the list to free up memory.
		
		super.onPause();
	}
	
	@Override
	protected void onResume() {
		super.onResume();				
		
		if ( spinner.getSelectedItemPosition() != this.lastSelection.ordinal() ) {
			spinner.setSelection(this.lastSelection.ordinal());
		} else {
			new LoadBooksTask().execute(this.lastSelection);
		}
	}
	
	/**
	 * Based on example found here:
	 * http://www.vogella.de/articles/AndroidListView/article.html
	 * 
	 * @author work
	 *
	 */
	private class BookAdapter extends QueryResultAdapter<LibraryBook> {	
		
		private Context context;
		
		public BookAdapter(Context context) {
			this.context = context;
		}		
		
		
		@Override
		public View getView(int index, LibraryBook book, View convertView,
				ViewGroup parent) {
			
			View rowView;
			
			if ( convertView == null ) {			
				LayoutInflater inflater = (LayoutInflater) context
					.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				rowView = inflater.inflate(R.layout.book_row, parent, false);
			} else {
				rowView = convertView;
			}
			
			TextView titleView = (TextView) rowView.findViewById(R.id.bookTitle);
			TextView authorView = (TextView) rowView.findViewById(R.id.bookAuthor);
			TextView dateView = (TextView) rowView.findViewById(R.id.addedToLibrary);
			
			ImageView imageView = (ImageView) rowView.findViewById(R.id.bookCover);
						
			authorView.setText("by " + book.getAuthor().getFirstName() + " " + book.getAuthor().getLastName() );
			titleView.setText(book.getTitle());
			
			dateView.setText( "Added on " + DATE_FORMAT.format(book.getAddedToLibrary()));
			
			if ( book.getCoverImage() != null ) {
				byte[] cover = book.getCoverImage();
				imageView.setImageBitmap( BitmapFactory.decodeByteArray(cover, 0, cover.length ));
			} else {
				imageView.setImageDrawable(backupCover);
			}
			
			return rowView;
		}	
	
	}	

	
	private class MenuSelectionListener implements OnItemSelectedListener {
		@Override
		public void onItemSelected(AdapterView<?> arg0, View arg1, int pos,
				long arg3) {
			
			Selections newSelections = Selections.values()[pos];
			
			lastSelection = newSelections;
			bookAdapter.clear();		
						
			new LoadBooksTask().execute(newSelections);
		}
		
		@Override
		public void onNothingSelected(AdapterView<?> arg0) {
						
		}
	}	
	
	private class ImportBooksTask extends AsyncTask<File, Integer, Void> implements OnCancelListener {	
		
		private boolean hadError = false;
		
		private static final int UPDATE_FOLDER = 1;
		private static final int UPDATE_IMPORT = 2;
		
		private int foldersScanned = 0;
		private int booksImported = 0;
		
		private boolean oldKeepScreenOn;
		private boolean keepRunning;	
		
		@Override
		protected void onPreExecute() {
			importDialog.setTitle("Importing books...");
			importDialog.setMessage("Scanning for EPUB files.");
			importDialog.setOnCancelListener(this);
			importDialog.show();			
			
			this.keepRunning = true;
			
			this.oldKeepScreenOn = listView.getKeepScreenOn();
			listView.setKeepScreenOn(true);
		}		
		
		@Override
		public void onCancel(DialogInterface dialog) {
			LOG.debug("User aborted import.");
			this.keepRunning = false;			
		}
		
		@Override
		protected Void doInBackground(File... params) {
			File parent = params[0];
			List<File> books = new ArrayList<File>();			
			findEpubsInFolder(parent, books);
			
			int total = books.size();
			int i = 0;			
	        
			while ( i < books.size() && keepRunning ) {
				
				File book = books.get(i);
				
				LOG.info("Importing: " + book.getAbsolutePath() );
				try {
					if ( ! libraryService.hasBook(book.getName() ) ) {
						importBook( book.getAbsolutePath() );
					}					
				} catch (OutOfMemoryError oom ) {
					hadError = true;
					return null;
				}
				
				i++;
				publishProgress(UPDATE_IMPORT, i, total);
				booksImported++;
			}
			
			return null;
		}
		
		private void findEpubsInFolder( File folder, List<File> items) {
			
			if ( folder == null || folder.getAbsolutePath().startsWith(LibraryService.BASE_LIB_PATH) ) {
				return;
			}			
			
			if ( folder.isDirectory() && folder.listFiles() != null) {
				
				for (File child: folder.listFiles() ) {
					findEpubsInFolder(child, items); 
				}
				
				foldersScanned++;
				publishProgress(UPDATE_FOLDER, foldersScanned);
				
			} else {
				if ( folder.getName().endsWith(".epub") ) {
					items.add(folder);
				}
			}
		}
		
		private void importBook(String file) {
			try {
				// read epub file
		        EpubReader epubReader = new EpubReader();
		        				
				Book importedBook = epubReader.readEpubLazy(file, "UTF-8", Arrays.asList(MediatypeService.mediatypes));								
				
				boolean copy = settings.getBoolean("copy_to_library", true);
	        	libraryService.storeBook(file, importedBook, false, copy);	        		        	
				
			} catch (Exception io ) {
				hadError = true;
				LOG.error("Error while reading book: " + file, io); 
			}
		}
		
		@Override
		protected void onProgressUpdate(Integer... values) {
			
			if ( values[0] == UPDATE_IMPORT ) {
				importDialog.setMessage("Importing " + values[1] + " / " + values[2]);
			} else {
				importDialog.setMessage("Scanning for EPUB files.\nFolders scanned: " + values[1] );
			}
		}
		
		@Override
		protected void onPostExecute(Void result) {
			
			importDialog.hide();			
			
			//If the user cancelled the import, don't bug him/her with alerts.
			if ( hadError && keepRunning ) {
				AlertDialog.Builder builder = new AlertDialog.Builder(LibraryActivity.this);
				builder.setTitle("Error while importing books.");
				builder.setMessage( "Could not import all books. Please try again." );
				builder.setNeutralButton("OK", new OnClickListener() {
					
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();						
					}
				});
				
				builder.show();
			}
			
			listView.setKeepScreenOn(oldKeepScreenOn);
			
			if ( booksImported > 0 ) {			
				//Switch to the "recently added" view.
				spinner.setSelection(Selections.LAST_ADDED.ordinal());				
			} else {
				AlertDialog.Builder builder = new AlertDialog.Builder(LibraryActivity.this);
				builder.setTitle("No books found.");
				builder.setMessage( "No books were found on your device.\nYou can download books at gutenberg.org or feedbooks.com" );
				builder.setNeutralButton("OK", new OnClickListener() {
					
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();						
					}
				});
				
				builder.show();
			}
		}
	}
	
	private class LoadBooksTask extends AsyncTask<Selections, Integer, QueryResult<LibraryBook>> {		
		
		private Selections sel;
		
		@Override
		protected void onPreExecute() {
			waitDialog.setTitle("Loading library...");
			waitDialog.show();
		}
		
		@Override
		protected QueryResult<LibraryBook> doInBackground(Selections... params) {
			
			this.sel = params[0];
			
			switch ( sel ) {			
			case LAST_ADDED:
				return libraryService.findAllByLastAdded();
			case UNREAD:
				return libraryService.findUnread();
			case BY_TITLE:
				return libraryService.findAllByTitle();
			case BY_AUTHOR:
				return libraryService.findAllByAuthor();
			default:
				return libraryService.findAllByLastRead();
			}			
		}
		
		@Override
		protected void onPostExecute(QueryResult<LibraryBook> result) {
			bookAdapter.setResult(result);
			waitDialog.hide();			
			
			if ( sel == Selections.LAST_ADDED && result.getSize() == 0 ) {
				
				AlertDialog.Builder builder = new AlertDialog.Builder(LibraryActivity.this);
				builder.setTitle("No books found.");
				builder.setMessage( "There are no books in your library.\nWould you like to scan your device for books?" );
				builder.setPositiveButton("Yes", new OnClickListener() {
					
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();		
						new ImportBooksTask().execute(new File("/sdcard"));
					}
				});
				
				builder.setNegativeButton("Not right now", new OnClickListener() {
					
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();						
					}
				});				
				
				builder.show();
			}
		}
		
	}
	
	
	
}
