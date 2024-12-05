import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SortedFile manages records stored in sorted order across pages.
 * Provides insertion, deletion, search, and range query functionality.
 */
public class SortedFile {
    private PageDirectory pageDirectory;
    private String dataFilename;
    private String directoryFilename;

    // Static counters for disk I/O statistics
    private static int diskReadCount = 0;
    private static int diskWriteCount = 0;

    /**
     * Constructs a SortedFile instance with specified filenames for data and directory.
     *
     * @param dataFilename Path to the data file.
     * @param directoryFilename Path to the directory file.
     * @throws IOException If an I/O error occurs while loading the directory.
     */
    public SortedFile(String dataFilename, String directoryFilename) throws IOException {
        this.dataFilename = dataFilename;
        this.directoryFilename = directoryFilename;
        this.pageDirectory = readDirectoryFromDisk();
    }

    /**
     * Inserts a record into the sorted file.
     *
     * @param record The record to insert.
     * @throws IOException If an I/O error occurs during the operation.
     */
    public void insertRecord(Record record) throws IOException {
        List<PageInfo> pages = pageDirectory.getPages();

        // Try to insert the record in an existing page
        for (PageInfo pageInfo : pages) {
            Page page = readPageFromDisk(pageInfo.getOffset());
            int numRecords = page.getNumberOfRecords();

            // If there is space in the current page
            if (numRecords < Page.SLOT_COUNT) {
                int insertPos = numRecords;

                // Locate the correct position for insertion based on the sorted order
                for (int i = 0; i < numRecords; i++) {
                    if (page.getRecord(i).getKey() > record.getKey()) {
                        insertPos = i;
                        break;
                    }
                }

                // Shift records to the right to make space for the new record
                for (int i = numRecords - 1; i >= insertPos; i--) {
                    if (page.isSlotUsed(i)) { // Check if the slot is used
                        Record temp = page.getRecord(i);
                        page.insertRecord(i + 1, temp); // Move the record to the next slot
                        page.setSlotUsed(i + 1, true);  // Mark the next slot as used
                        page.setSlotUsed(i, false);  // Clear the current slot
                    }
                }

                // Insert the new record at the correct position
                page.insertRecord(insertPos, record);
                page.setSlotUsed(insertPos, true); // Mark the newly inserted slot as used

                // Update free slot count and save the page to disk
                writePageToDisk(page, pageInfo);
                pageInfo.setFreeSlots(pageInfo.getFreeSlots() - 1);
                writeDirectoryToDisk();

                // After insert, balance pages
                balancePages();
                return;
            }
        }

        // If no page has space, create a new page
        Page newPage = new Page();
        newPage.insertRecord(0, record);  // Insert record at the first position
        newPage.setSlotUsed(0, true);     // Mark the first slot as used
        PageInfo newPageInfo = new PageInfo((long) (pages.size() * Page.PAGE_SIZE), Page.SLOT_COUNT - 1);
        pageDirectory.addPage(newPageInfo);
        writePageToDisk(newPage, newPageInfo);
        writeDirectoryToDisk();

        // After adding a new page, balance pages
        balancePages();
    }

    /**
     * Searches for a record by its key.
     *
     * @param key The key of the record to search for.
     * @return The matching record, or null if not found.
     * @throws IOException If an I/O error occurs during the operation.
     */
    public Record searchRecord(int key) throws IOException {
        List<PageInfo> pages = pageDirectory.getPages();
        for (PageInfo pageInfo : pages) {
            Page page = readPageFromDisk(pageInfo.getOffset());
            int low = 0, high = page.getNumberOfRecords() - 1;
            while (low <= high) {
                int mid = (low + high) / 2;
                Record midRecord = page.getRecord(mid);
                if (midRecord.getKey() == key) {
                    return midRecord;
                } else if (midRecord.getKey() < key) {
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
        }
        return null;
    }

    /**
     * Deletes a record by its key.
     *
     * @param key The key of the record to delete.
     * @return True if the record was successfully deleted, false otherwise.
     * @throws IOException If an I/O error occurs during the operation.
     */
    public boolean deleteRecord(int key) throws IOException {
        List<PageInfo> pages = pageDirectory.getPages();

        for (PageInfo pageInfo : pages) {
            Page page = readPageFromDisk(pageInfo.getOffset());
            int numRecords = page.getNumberOfRecords();

            for (int i = 0; i < numRecords; i++) {
                Record record = page.getRecord(i);
                if (record.getKey() == key) {
                    // Shift records to the left to fill the gap
                    for (int j = i; j < numRecords - 1; j++) {
                        Record temp = page.getRecord(j + 1);
                        page.setSlotUsed(j, false);  // Mark the current slot as unused
                        page.insertRecord(j, temp); // Move the record to the left slot
                        page.setSlotUsed(j + 1, false); // Mark the next slot as unused
                    }

                    // Clear the last slot after shifting
                    page.setSlotUsed(numRecords - 1, false); // Mark the last slot as unused
                    page.getRecords()[numRecords - 1] = null; // Clear the record in the last slot explicitly

                    // Update the page on disk
                    writePageToDisk(page, pageInfo);

                    // Update free slot count and directory
                    pageInfo.setFreeSlots(pageInfo.getFreeSlots() + 1);
                    writeDirectoryToDisk();

                    // After delete, balance pages
                    balancePages();
                    return true; // Record successfully deleted
                }
            }
        }
        return false; // Record not found
    }

    private void balancePages() throws IOException {
        List<PageInfo> pages = pageDirectory.getPages();
        for (int i = 0; i < pages.size() - 1; i++) {
            PageInfo currentPageInfo = pages.get(i);
            PageInfo nextPageInfo = pages.get(i + 1);

            Page currentPage = readPageFromDisk(currentPageInfo.getOffset());
            Page nextPage = readPageFromDisk(nextPageInfo.getOffset());

            // If current page is too empty, move records from next page
            while (currentPage.getNumberOfRecords() < Page.SLOT_COUNT / 2 &&
                    nextPage.getNumberOfRecords() > 0) {
                Record firstRecord = nextPage.getRecord(0);
                nextPage.deleteRecord(0);
                currentPage.insertRecord(currentPage.getNumberOfRecords(), firstRecord);

                currentPageInfo.setFreeSlots(currentPageInfo.getFreeSlots() - 1);
                nextPageInfo.setFreeSlots(nextPageInfo.getFreeSlots() + 1);

                writePageToDisk(currentPage, currentPageInfo);
                writePageToDisk(nextPage, nextPageInfo);
            }
        }
        writeDirectoryToDisk();
    }


    /**
     * Performs a range search for records with keys within the specified bounds.
     *
     * @param lowerBound The lower bound of the range (inclusive).
     * @param upperBound The upper bound of the range (inclusive).
     * @return A list of matching records.
     * @throws IOException If an I/O error occurs during the operation.
     */
    public List<Record> rangeSearch(int lowerBound, int upperBound) throws IOException {
        List<Record> result = new ArrayList<>();
        List<PageInfo> pages = pageDirectory.getPages();
        for (PageInfo pageInfo : pages) {
            Page page = readPageFromDisk(pageInfo.getOffset());
            for (int i = 0; i < page.getNumberOfRecords(); i++) {
                Record record = page.getRecord(i);
                if (record.getKey() >= lowerBound && record.getKey() <= upperBound) {
                    result.add(record);
                }
            }
        }
        return result;
    }

    /**
     * Prints all pages and their records in the sorted file.
     *
     * @throws IOException If an I/O error occurs during the operation.
     */
    public void printAllPages() throws IOException {
        System.out.println("\nSortedFile Pages:");
        List<PageInfo> pages = pageDirectory.getPages();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            PageInfo pageInfo = pages.get(pageIndex);
            Page page = readPageFromDisk(pageInfo.getOffset());
            System.out.print("Page " + pageIndex + ": ");
            page.printAllRecords();
        }
    }

    // Helper method to read the page directory from disk
    private PageDirectory readDirectoryFromDisk() throws IOException {
        File dirFile = new File(directoryFilename);
        if (!dirFile.exists()) {
            return new PageDirectory();
        }
        try (FileInputStream fis = new FileInputStream(directoryFilename)) {
            byte[] data = fis.readAllBytes();
            diskReadCount++;
            return PageDirectory.fromByteArray(data);
        } catch (ClassNotFoundException e) {
            throw new IOException("Failed to load page directory", e);
        }
    }

    // Helper method to write the page directory to disk
    private void writeDirectoryToDisk() throws IOException {
        try (FileOutputStream fos = new FileOutputStream(directoryFilename)) {
            fos.write(pageDirectory.toByteArray());
            diskWriteCount++;
        }
    }

    // Helper method to read a page from disk at the specified offset
    private Page readPageFromDisk(long offset) throws IOException {
        byte[] bytes = new byte[Page.PAGE_SIZE];
        try (RandomAccessFile raf = new RandomAccessFile(dataFilename, "r")) {
            raf.seek(offset);
            raf.readFully(bytes);
            diskReadCount++;
        }
        return Page.fromByteArray(bytes);
    }

    // Helper method to write a page to disk
    private void writePageToDisk(Page page, PageInfo pageInfo) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(dataFilename, "rw")) {
            raf.seek(pageInfo.getOffset());
            raf.write(page.toByteArray());
            diskWriteCount++;
        }
    }

    // Methods to access disk I/O statistics
    public static int getDiskReadCount() {
        return diskReadCount;
    }

    public static int getDiskWriteCount() {
        return diskWriteCount;
    }

    public static void resetDiskIOCounters() {
        diskReadCount = 0;
        diskWriteCount = 0;
    }
}
