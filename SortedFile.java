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
        for (PageInfo pageInfo : pages) {
            Page page = readPageFromDisk(pageInfo.getOffset());
            if (page.getNumberOfRecords() < Page.SLOT_COUNT) {
                insertIntoPage(page, pageInfo, record);
                return;
            }
        }

        Page newPage = new Page();
        PageInfo newPageInfo = new PageInfo(getNextPageOffset(), 0);
        insertIntoPage(newPage, newPageInfo, record);
        pageDirectory.addPage(newPageInfo);
        writeDirectoryToDisk();
    }

    // Add this method within the SortedFile class
    private long getNextPageOffset() throws IOException {
        File file = new File(dataFilename);
        if (!file.exists()) {
            // If the file does not exist, return 0 as the starting offset
            return 0;
        }
        return file.length();
    }
    private void insertIntoPage(Page page, PageInfo pageInfo, Record record) throws IOException {
        int i;
        for (i = 0; i < Page.SLOT_COUNT; i++) {
            if (!page.isSlotUsed(i)) {
                page.insertRecord(i, record);
                pageInfo.setFreeSlots(Page.SLOT_COUNT - page.getNumberOfRecords());
                writePageToDisk(page, pageInfo);
                return;
            }
        }
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
            for (int i = 0; i < Page.SLOT_COUNT; i++) {
                if (page.isSlotUsed(i)) {
                    Record record = page.getRecord(i);
                    if (record.getKey() == key) {
                        return record;
                    }
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
            for (int i = 0; i < Page.SLOT_COUNT; i++) {
                if (page.isSlotUsed(i)) {
                    Record record = page.getRecord(i);
                    if (record.getKey() == key) {
                        page.deleteRecord(i);
                        pageInfo.setFreeSlots(Page.SLOT_COUNT - page.getNumberOfRecords());
                        writePageToDisk(page, pageInfo);
                        return true;
                    }
                }
            }
        }
        return false;
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
            for (int i = 0; i < Page.SLOT_COUNT; i++) {
                if (page.isSlotUsed(i)) {
                    Record record = page.getRecord(i);
                    if (record.getKey() >= lowerBound && record.getKey() <= upperBound) {
                        result.add(record);
                    }
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
